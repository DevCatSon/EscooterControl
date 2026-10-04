package com.devcat.escootercontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import java.util.UUID

private const val TAG = "ScooterBleManager"

// Two candidate service/characteristic UUID pairs found in the decompiled app.
// FEE0/FEE2 is tried first, then FFF0/FFF1 (same order as the stock app).
private val SERVICE_FEE0: UUID = UUID.fromString("0000FEE0-0000-1000-8000-00805F9B34FB")
private val CHAR_FEE2: UUID = UUID.fromString("0000FEE2-0000-1000-8000-00805F9B34FB")
private val SERVICE_FFF0: UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
private val CHAR_FFF1: UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private val CHANNEL_CANDIDATES = listOf(SERVICE_FEE0 to CHAR_FEE2, SERVICE_FFF0 to CHAR_FFF1)

// BLE advertisement localName prefixes the app scans for.
val SCOOTER_NAME_PREFIXES = listOf(
    "E-S", "e-s", "VC_", "VC-", "vc_", "vc-", "SN", "sn", "SN_", "sn_", "vA-", "VA-", "va-", "ZL"
)

private const val SCAN_TIMEOUT_MS = 15_000L      // stock app stops after 10s; a little slack
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val MTU_TIMEOUT_MS = 2_000L        // requestMtu can silently never call back
private const val HANDSHAKE_DELAY_MS = 1_000L    // stock app waits 1s after notify is enabled
private const val OP_TIMEOUT_MS = 1_500L         // a write callback that never arrives must not wedge the queue
private const val CONFIG_QUERY_SPACING_MS = 150L

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING_SERVICES, READY }

data class DiscoveredDevice(val device: BluetoothDevice, val name: String, val rssi: Int)

@SuppressLint("MissingPermission") // permission checks are done by the caller (ViewModel/UI) before invoking these
class ScooterBleManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter get() = bluetoothManager.adapter
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Hardware-reported state is kept separate from the UI snapshot. The UI snapshot may
    // temporarily contain optimistic control values while the scooter confirms a write.
    private val _reportedTelemetry = MutableStateFlow(ScooterTelemetry())
    private val _telemetry = MutableStateFlow(ScooterTelemetry())
    val telemetry: StateFlow<ScooterTelemetry> = _telemetry

    private val _scanResults = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val scanResults: StateFlow<List<DiscoveredDevice>> = _scanResults

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError

    /** One-shot user-facing notices (connection failures, "not connected", etc.). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()
    fun postMessage(text: String) { _messages.tryEmit(text) }

    /** Address and advertised name of the scooter we are connecting/connected to (for saving and renaming). */
    private val _connectedAddress = MutableStateFlow<String?>(null)
    val connectedAddress: StateFlow<String?> = _connectedAddress
    private val _connectedName = MutableStateFlow<String?>(null)
    val connectedName: StateFlow<String?> = _connectedName

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode

    /** Rolling TX/RX log for the Debug screen. */
    val frameLog = FrameLog()

    private var lastScanStartAttempt = 0L

    // Tracks the last lockCarState/bluetoothBindingStatus combo we auto-acked, so we
    // don't resend on every telemetry tick once it's already been sent once.
    private var lastAutoBindAckFor: Pair<Boolean?, Boolean?>? = null

    // Last status-frame payload we logged, so the ~1s status heartbeat doesn't drown the log.
    private var lastLoggedStatusHex: String? = null
    private val lastLoggedHexByCode = java.util.concurrent.ConcurrentHashMap<Int, String>()

    // After any switch command, log EVERY status frame for a few seconds so the Debug screen shows
    // exactly what the scooter did next (did the bit flip? did it flip back?).
    // When each opcode last arrived, so a config write can check the scooter actually answered it.
    private val lastRxAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val CONFIG_CONFIRM_MS = 1500L
    @Volatile private var traceUntilMs = 0L
    // Dashboard controls are optimistic and coalesced. Rapid taps update the UI immediately,
    // but only the final intent in a short burst is written to BLE. Pending values are overlaid
    // on hardware telemetry so an older status heartbeat cannot visually flip a control back.
    private val pendingToggleDesired = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    private val absolutePending = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val controlToken = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    private val nextControlToken = java.util.concurrent.atomic.AtomicLong(0L)
    private val CONTROL_COALESCE_MS = 70L
    private val CONTROL_RETRY_MS = 900L
    private val CONTROL_TIMEOUT_MS = 2200L
    private val TRACE_WINDOW_MS = 4000L

    // ---------------- GATT operation queue ----------------
    // Android BLE allows one in-flight GATT operation at a time. Every write goes through this
    // queue, drained by onCharacteristicWrite. Callbacks arrive on binder threads while the UI
    // enqueues from the main thread, so all queue state is guarded by queueLock, and a watchdog
    // frees the queue if a completion callback is ever lost.
    private val queueLock = Any()
    private val opQueue = ArrayDeque<() -> Unit>()
    private var opInFlight = false
    private var opToken = 0L

    private fun enqueue(op: () -> Unit) {
        synchronized(queueLock) { opQueue.addLast(op) }
        drainQueue()
    }

    private fun drainQueue() {
        var token = 0L
        val next: (() -> Unit)? = synchronized(queueLock) {
            if (opInFlight) null
            else opQueue.pollFirst()?.also { opInFlight = true; opToken++; token = opToken }
        }
        if (next == null) return
        mainHandler.postDelayed({ watchdog(token) }, OP_TIMEOUT_MS)
        next()
    }

    private fun completeOp() {
        synchronized(queueLock) { opInFlight = false; opToken++ }
        drainQueue()
    }

    private fun watchdog(token: Long) {
        val stalled = synchronized(queueLock) {
            if (opInFlight && opToken == token) { opInFlight = false; opToken++; true } else false
        }
        if (stalled) {
            frameLog.add(LogDirection.INFO, "write callback never arrived -- releasing queue")
            drainQueue()
        }
    }

    private fun clearQueue() {
        synchronized(queueLock) { opQueue.clear(); opInFlight = false; opToken++ }
    }

    // ---------------- Scanning ----------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (SCOOTER_NAME_PREFIXES.none { name.startsWith(it) }) return
            val current = _scanResults.value.toMutableList()
            if (current.none { it.device.address == result.device.address }) {
                current.add(DiscoveredDevice(result.device, name, result.rssi))
                _scanResults.value = current
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            mainHandler.removeCallbacks(scanTimeout)
            _connectionState.value = ConnectionState.DISCONNECTED
            _scanError.value = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED ->
                    "A scan is already running -- try again in a moment."
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Bluetooth scan registration failed. Toggle Bluetooth off/on and retry."
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR ->
                    "Bluetooth scan hit an internal error -- this usually means the " +
                        "system's scan rate limit was hit from repeated attempts. Toggle " +
                        "Bluetooth off/on, wait ~30s, then try again."
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    "This phone doesn't support the requested BLE scan mode."
                else -> "Scan failed (code $errorCode). Toggle Bluetooth off/on and retry."
            }
        }
    }

    private val scanTimeout = Runnable { stopScan() }

    fun startScan() {
        // Guard against rapid repeated taps hammering Android's BLE scan rate
        // limiter (roughly 5 start attempts per rolling 30s triggers
        // SCAN_FAILED_INTERNAL_ERROR system-wide, not just for this app).
        val now = System.currentTimeMillis()
        val state = _connectionState.value
        if (state == ConnectionState.SCANNING) return
        if (state != ConnectionState.DISCONNECTED) return // mid-connection: don't start a scan
        if (now - lastScanStartAttempt < 2000) {
            _scanError.value = "Scanning too fast -- wait a second and try again."
            return
        }
        lastScanStartAttempt = now

        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            _scanError.value = "Bluetooth is turned off -- switch it on and try again."
            return
        }
        val scanner = ad.bluetoothLeScanner
        if (scanner == null) {
            _scanError.value = "Bluetooth isn't ready yet -- try again in a moment."
            return
        }

        _scanResults.value = emptyList()
        _scanError.value = null
        _connectionState.value = ConnectionState.SCANNING
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        // Don't scan forever: it drains battery and burns the system scan-rate budget.
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeout)
        if (_connectionState.value == ConnectionState.SCANNING) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                // Permission can be revoked while a scan is running; state cleanup still matters.
            }
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    // ---------------- Connect / GATT ----------------

    private val connectTimeout = Runnable {
        if (_connectionState.value != ConnectionState.READY) {
            frameLog.add(LogDirection.INFO, "connect timed out")
            failConnection("Connecting timed out. Make sure the scooter is on, nearby and not " +
                "connected to another phone, then try again.")
        }
    }
    private val mtuTimeout = Runnable { markReady("MTU callback never arrived, continuing") }

    private class Channel(val notify: BluetoothGattCharacteristic, val write: BluetoothGattCharacteristic)

    private fun BluetoothGattCharacteristic.canWrite() =
        (properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

    private fun pickChannel(g: BluetoothGatt): Channel? {
        for ((serviceUuid, charUuid) in CHANNEL_CANDIDATES) {
            val service = g.getService(serviceUuid) ?: continue
            val notifyChar = service.getCharacteristic(charUuid) ?: continue
            // Same characteristic is used for write + notify on this protocol, but if a unit
            // exposes a separate writable characteristic in the same service, use that instead
            // of writing to something that can't be written.
            val writeChar = if (notifyChar.canWrite()) notifyChar
            else service.characteristics.firstOrNull { it.canWrite() } ?: notifyChar
            return Channel(notifyChar, writeChar)
        }
        return null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // The very first callback can race the assignment of `gatt` in connect().
            if (gatt == null && _connectionState.value == ConnectionState.CONNECTING) gatt = g
            if (g !== gatt) { // stale callback from a connection we already tore down
                if (newState == BluetoothProfile.STATE_DISCONNECTED) g.close()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    frameLog.add(LogDirection.INFO, "GATT connected (status=$status), discovering services")
                    _connectionState.value = ConnectionState.DISCOVERING_SERVICES
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = _connectionState.value == ConnectionState.READY
                    frameLog.add(LogDirection.INFO, "GATT disconnected (status=$status)")
                    teardown()
                    g.close() // always release the client interface, or Android runs out (error 133)
                    if (!wasReady && status != BluetoothGatt.GATT_SUCCESS) {
                        postMessage("Couldn't connect (Bluetooth error $status). Try again; if it " +
                            "keeps happening, toggle Bluetooth off and on.")
                    } else if (wasReady) {
                        postMessage("Scooter disconnected.")
                    }
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Service discovery failed (error $status). Try again.")
                return
            }
            val channel = pickChannel(g)
            if (channel == null) {
                val found = g.services.joinToString { it.uuid.toString().substring(4, 8) }
                frameLog.add(LogDirection.INFO, "no FEE0/FFF0 channel; services present: $found")
                failConnection("This device doesn't expose the expected scooter service.")
                return
            }
            writeCharacteristic = channel.write
            frameLog.add(
                LogDirection.INFO,
                "using service ${channel.notify.service.uuid.toString().substring(4, 8)} " +
                    "notify=${channel.notify.uuid.toString().substring(4, 8)} " +
                    "(props=0x${channel.notify.properties.toString(16)}) " +
                    "write=${channel.write.uuid.toString().substring(4, 8)} " +
                    "(props=0x${channel.write.properties.toString(16)})"
            )
            if (!enableNotifications(g, channel.notify)) {
                failConnection("Couldn't enable notifications from the scooter.")
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt || descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Couldn't enable notifications (error $status).")
                return
            }
            frameLog.add(LogDirection.INFO, "notifications enabled")
            afterNotifyEnabled(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            markReady("MTU=$mtu (status=$status)")
        }

        // API 33+ delivers the value directly...
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(value)
        }

        // ...but Android 12 and below only ever call this older overload. Overriding only the new
        // one means telemetry silently never arrives on those phones.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                characteristic.value?.let { handleNotify(it) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                frameLog.add(LogDirection.INFO, "write reported failure status=$status")
            }
            completeOp()
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            // Some cheap modules stream without a CCCD; carry on rather than failing outright.
            frameLog.add(LogDirection.INFO, "no CCCD descriptor -- assuming notifications are on")
            afterNotifyEnabled(g)
            return true
        }
        val indicateOnly = (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
            (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        cccd.value = if (indicateOnly) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return g.writeDescriptor(cccd)
    }

    /**
     * Runs only once the CCCD write has completed -- previously requestMtu() was fired straight
     * after writeDescriptor(), which Android rejects (one GATT op at a time), so onMtuChanged
     * could never fire and the app never reached READY or sent its handshake.
     */
    private fun afterNotifyEnabled(g: BluetoothGatt) {
        mainHandler.removeCallbacks(mtuTimeout)
        mainHandler.postDelayed(mtuTimeout, MTU_TIMEOUT_MS)
        if (!g.requestMtu(255)) markReady("requestMtu rejected, continuing")
    }

    private fun markReady(reason: String) {
        synchronized(this) {
            if (_connectionState.value != ConnectionState.DISCOVERING_SERVICES) return
            mainHandler.removeCallbacks(mtuTimeout)
            mainHandler.removeCallbacks(connectTimeout)
            frameLog.add(LogDirection.INFO, "link ready ($reason)")
            _connectionState.value = ConnectionState.READY
        }
        // Real app waits 1s after notify success before sending the handshake.
        mainHandler.postDelayed({
            if (_connectionState.value == ConnectionState.READY) sendHandshake()
        }, HANDSHAKE_DELAY_MS)
    }

    fun connect(device: BluetoothDevice, name: String? = null) {
        stopScan()
        _demoMode.value = false
        teardown()?.let { it.disconnect(); it.close() }
        _reportedTelemetry.value = ScooterTelemetry()
        _telemetry.value = ScooterTelemetry() // never show/act on state from a previous session
        lastLoggedStatusHex = null
        lastLoggedHexByCode.clear()
        _connectedAddress.value = device.address
        _connectedName.value = name ?: device.name
        _connectionState.value = ConnectionState.CONNECTING
        frameLog.add(LogDirection.INFO, "connecting to ${device.address}")
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
        // TRANSPORT_LE stops dual-mode phones from trying a classic-Bluetooth link first.
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Connects straight to a known address with no scan, like the stock app's saved-device reconnect.
     * Returns false (and sets a scan error the UI shows) if Bluetooth is off or the address is invalid.
     */
    fun connectToAddress(address: String, name: String?): Boolean {
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            _scanError.value = "Bluetooth is turned off -- switch it on and try again."
            return false
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        if (_connectionState.value != ConnectionState.DISCONNECTED) return false
        val device = try { ad.getRemoteDevice(address) } catch (e: IllegalArgumentException) { return false }
        _scanError.value = null
        connect(device, name)
        return true
    }

    fun disconnect() {
        val g = teardown()
        g?.disconnect()
        g?.close()
        _demoMode.value = false
        _connectedAddress.value = null
        _connectedName.value = null
        _reportedTelemetry.value = ScooterTelemetry()
        _telemetry.value = ScooterTelemetry()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Starts a completely local simulated session. No Bluetooth GATT object is opened. */
    fun enterDemoMode(name: String = "Demo Scooter") {
        stopScan()
        teardown()?.let { it.disconnect(); it.close() }
        _demoMode.value = true
        _scanError.value = null
        _connectedAddress.value = DEMO_ADDRESS
        _connectedName.value = name
        val demo = demoTelemetry()
        _reportedTelemetry.value = demo
        _telemetry.value = demo
        _connectionState.value = ConnectionState.READY
        frameLog.add(LogDirection.INFO, "Demo Mode started -- Bluetooth writes are simulated locally")
        postMessage("Demo Mode: controls are simulated and no Bluetooth commands are sent.")
    }

    private fun failConnection(message: String) {
        postMessage(message)
        disconnect()
    }

    /**
     * Drops all per-connection state and hands back the GATT that was in use (or null) so the
     * caller can disconnect/close it exactly once. Safe to call repeatedly.
     */
    private fun teardown(): BluetoothGatt? {
        mainHandler.removeCallbacks(connectTimeout)
        mainHandler.removeCallbacks(mtuTimeout)
        clearQueue()
        val g = gatt
        gatt = null
        writeCharacteristic = null
        lastAutoBindAckFor = null
        lastRxAt.clear()
        pendingToggleDesired.clear()
        absolutePending.clear()
        controlToken.clear()
        return g
    }

    // ---------------- Incoming ----------------

    private fun handleNotify(value: ByteArray) {
        val frames = ProtocolCodec.parseAll(value)
        if (frames.isEmpty()) {
            frameLog.add(LogDirection.RX, "unparseable (${value.size}B): ${ProtocolCodec.bytesToHex(value)}")
            return
        }
        for (frame in frames) {
            lastRxAt[frame.code] = System.currentTimeMillis()
            logRx(frame)
            // The stock app never verifies incoming checksums, so neither do we drop on them --
            // a wrong assumption about the formula would otherwise blank out all telemetry.
            val decoded = TelemetryDecoder.apply(_reportedTelemetry.value, frame)
            _reportedTelemetry.value = decoded
            reconcilePendingFromFrame(frame.code, decoded)
            publishTelemetry()
            maybeAutoSendBindAck()
        }
    }

    private fun logRx(frame: ProtocolCodec.IncomingFrame) {
        val hex = ProtocolCodec.bytesToHex(frame.data)
        when (frame.code) {
            ProtocolCodec.CODE_TELEMETRY_MOTOR -> return // ~1Hz live values, would flood the log
            ProtocolCodec.CODE_TELEMETRY_STATUS -> {
                val tracing = System.currentTimeMillis() < traceUntilMs
                if (!tracing && hex == lastLoggedStatusHex) return // only log when a bit changed
                lastLoggedStatusHex = hex
                val b0 = frame.data.getOrNull(0)?.toInt()?.and(0xFF) ?: 0
                frameLog.add(
                    LogDirection.RX,
                    "status data=$hex  headlight=${(b0 shr 3) and 1} startup=${(b0 shr 5) and 1} " +
                        "cruise=${(b0 shr 6) and 1} zt=%02X".format(frame.zt)
                )
                return
            }
        }
        // 18/19/20 stream about once a second. Log a change in 18; 19/20 only inside a command trace.
        if (frame.code == ProtocolCodec.CODE_TELEMETRY_VERSION || frame.code == ProtocolCodec.CODE_TELEMETRY_SENSORS || frame.code == 20) {
            val tracing = System.currentTimeMillis() < traceUntilMs
            val unchanged = lastLoggedHexByCode.put(frame.code, hex) == hex
            if (unchanged || (frame.code != ProtocolCodec.CODE_TELEMETRY_VERSION && !tracing)) return
        }
        val note = when (frame.code) {
            ProtocolCodec.CODE_SET_MAX_SPEED -> " max speed=${frame.data.firstOrNull()?.toInt()?.and(0xFF)}"
            ProtocolCodec.CODE_SET_START_TORQUE,
            ProtocolCodec.CODE_SET_MAX_TORQUE,
            ProtocolCodec.CODE_SET_BRAKE_STRENGTH -> " raw=${frame.data.firstOrNull()?.toInt()?.and(0xFF)}"
            else -> ""
        }
        val chk = if (frame.checksumOk) "" else " (checksum mismatch)"
        frameLog.add(LogDirection.RX, "code=${frame.code} data=$hex$note$chk")
    }

    // ---------------- Outgoing ----------------

    private fun sendHandshake() {
        // The real sequence, confirmed from onBLENotifyResult() (fires on every normal
        // connection, not just first-time pairing), is these two literal frames.
        ProtocolCodec.HANDSHAKE_FRAMES.forEach { writeRaw(it, "handshake") }
    }

    /** Sends a command using the given opcode/data, using the last-known ZT sync byte. */
    fun sendCommand(code: Int, data: List<Int>): Boolean {
        if (_demoMode.value) {
            applyDemoCommand(code, data)
            return true
        }
        val zt = _reportedTelemetry.value.lastZt
        val frame = ProtocolCodec.buildCommand(zt, code, data)
        return writeRaw(frame, "code=$code data=$data zt=%02X".format(zt))
    }

    /** Debug-screen entry point: send an arbitrary code/data pair and trace the scooter's reaction. */
    fun sendRaw(code: Int, data: List<Int>): Boolean {
        traceUntilMs = System.currentTimeMillis() + TRACE_WINDOW_MS
        return sendCommand(code, data)
    }

    /** Absolute-value setting in the units written by the protocol. */
    private fun absoluteValue(t: ScooterTelemetry, code: Int): Int? = when (code) {
        ProtocolCodec.CODE_SET_GEAR -> ProtocolCodec.uiGear(t.gear)
        ProtocolCodec.CODE_WORK_MODE -> t.workMode
        ProtocolCodec.CODE_SPEED_UNIT -> t.speedUnitMiles?.let {
            if (it) ProtocolCodec.UNIT_MILE_VALUE else ProtocolCodec.UNIT_KM_VALUE
        }
        else -> null
    }

    /** State bit associated with a dashboard toggle command. */
    private fun stateValue(t: ScooterTelemetry, code: Int): Boolean? = when (code) {
        ProtocolCodec.CODE_HEADLIGHT -> t.headlightOn
        ProtocolCodec.CODE_CRUISE -> t.cruiseOn
        ProtocolCodec.CODE_STARTUP_MODE -> t.startupModeOn
        ProtocolCodec.CODE_LOCK_TOGGLE -> t.locked
        else -> null
    }

    private fun applyPendingOverrides(base: ScooterTelemetry): ScooterTelemetry {
        val pendingGear = absolutePending[ProtocolCodec.CODE_SET_GEAR]
        val pendingUnit = absolutePending[ProtocolCodec.CODE_SPEED_UNIT]
        val pendingWorkMode = absolutePending[ProtocolCodec.CODE_WORK_MODE]
        return base.copy(
            locked = pendingToggleDesired[ProtocolCodec.CODE_LOCK_TOGGLE] ?: base.locked,
            headlightOn = pendingToggleDesired[ProtocolCodec.CODE_HEADLIGHT] ?: base.headlightOn,
            cruiseOn = pendingToggleDesired[ProtocolCodec.CODE_CRUISE] ?: base.cruiseOn,
            startupModeOn = pendingToggleDesired[ProtocolCodec.CODE_STARTUP_MODE] ?: base.startupModeOn,
            gear = pendingGear ?: base.gear,
            speedUnitMiles = pendingUnit?.let { it == ProtocolCodec.UNIT_MILE_VALUE } ?: base.speedUnitMiles,
            workMode = pendingWorkMode ?: base.workMode
        )
    }

    private fun publishTelemetry() {
        _telemetry.value = applyPendingOverrides(_reportedTelemetry.value)
    }

    /** Only authoritative frames can settle pending UI intent; unrelated frames retain the overlay. */
    private fun reconcilePendingFromFrame(frameCode: Int, decoded: ScooterTelemetry) {
        if (frameCode == ProtocolCodec.CODE_TELEMETRY_STATUS) {
            pendingToggleDesired.forEach { (code, desired) ->
                if (stateValue(decoded, code) == desired) clearPendingControl(code)
            }
            absolutePending.forEach { (code, desired) ->
                if ((code == ProtocolCodec.CODE_SET_GEAR || code == ProtocolCodec.CODE_SPEED_UNIT) &&
                    absoluteValue(decoded, code) == desired
                ) clearPendingControl(code)
            }
        }
        if (frameCode == ProtocolCodec.CODE_WORK_MODE) {
            absolutePending[ProtocolCodec.CODE_WORK_MODE]?.let { desired ->
                if (absoluteValue(decoded, ProtocolCodec.CODE_WORK_MODE) == desired) {
                    clearPendingControl(ProtocolCodec.CODE_WORK_MODE)
                }
            }
        }
    }

    private fun clearPendingControl(code: Int) {
        pendingToggleDesired.remove(code)
        absolutePending.remove(code)
        controlToken.remove(code)
    }

    /**
     * Collapses a burst of taps into one BLE write. Newer intent invalidates retry/timeout work
     * from older taps, preventing both queue buildup and late writes that undo the user's choice.
     */
    private fun scheduleControlWrite(code: Int, data: List<Int>, label: String): Boolean {
        val token = nextControlToken.incrementAndGet()
        controlToken[code] = token
        traceUntilMs = System.currentTimeMillis() + TRACE_WINDOW_MS

        mainHandler.postDelayed({
            if (controlToken[code] != token || _connectionState.value != ConnectionState.READY) return@postDelayed
            frameLog.add(LogDirection.INFO, "$label: sending latest intent data=$data")
            if (!sendCommand(code, data)) {
                clearPendingControl(code)
                publishTelemetry()
                return@postDelayed
            }

            mainHandler.postDelayed({
                if (controlToken[code] == token && _connectionState.value == ConnectionState.READY) {
                    frameLog.add(LogDirection.INFO, "$label: not confirmed after ${CONTROL_RETRY_MS}ms -- retrying latest intent once")
                    sendCommand(code, data)
                }
            }, CONTROL_RETRY_MS)

            mainHandler.postDelayed({
                if (controlToken[code] == token) {
                    clearPendingControl(code)
                    publishTelemetry()
                    frameLog.add(LogDirection.INFO, "$label: confirmation timed out -- restored reported state")
                    postMessage("$label wasn't confirmed by the scooter.")
                }
            }, CONTROL_TIMEOUT_MS)
        }, CONTROL_COALESCE_MS)
        return true
    }

    /** Absolute controls (gear/unit/work mode) update immediately and coalesce rapid changes. */
    private fun sendAbsolute(code: Int, desired: Int, label: String): Boolean {
        if (_demoMode.value) return sendCommand(code, listOf(desired))
        if (absoluteValue(_telemetry.value, code) == desired && absolutePending[code] == null) return true

        absolutePending[code] = desired
        publishTelemetry()
        return scheduleControlWrite(code, listOf(desired), label)
    }

    /** Dashboard toggle with immediate optimistic UI and a coalesced absolute protocol byte. */
    private fun sendStateful(code: Int, stateBefore: Boolean, value: Int): Boolean {
        if (_demoMode.value) return sendCommand(code, listOf(value))

        val desired = !stateBefore
        pendingToggleDesired[code] = desired
        publishTelemetry()
        return scheduleControlWrite(code, listOf(value), when (code) {
            ProtocolCodec.CODE_HEADLIGHT -> "Headlight"
            ProtocolCodec.CODE_CRUISE -> "Cruise"
            ProtocolCodec.CODE_STARTUP_MODE -> "Kick start"
            ProtocolCodec.CODE_LOCK_TOGGLE -> "Lock"
            else -> "Control $code"
        })
    }

    private fun sendSwitch(code: Int, currentlyOn: Boolean): Boolean =
        sendStateful(code, currentlyOn, ProtocolCodec.switchValue(currentlyOn))

    @Suppress("DEPRECATION")
    private fun writeRaw(bytes: ByteArray, note: String): Boolean {
        val g = gatt
        val c = writeCharacteristic
        if (g == null || c == null || _connectionState.value != ConnectionState.READY) {
            frameLog.add(LogDirection.INFO, "dropped $note -- not connected")
            return false
        }
        frameLog.add(LogDirection.TX, "$note  ${ProtocolCodec.bytesToHex(bytes)}")
        enqueue {
            // Stock app uses write-without-response on Android; fall back if the characteristic
            // only advertises a normal write.
            c.writeType =
                if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = bytes
            if (!g.writeCharacteristic(c)) {
                frameLog.add(LogDirection.INFO, "writeCharacteristic() refused $note")
                completeOp() // synchronous failure: unblock the queue
            }
        }
        return true
    }

    /**
     * Replicates a behavior found in the decompiled app: after telemetry shows a
     * "mismatched" lockCarState/bluetoothBindingStatus combo, it auto-sends a CODE 76
     * bind acknowledgement. In the original code this was gated behind a server-side
     * `deviceInfo.versionType == 1` flag we have no way to read over BLE, so this
     * always attempts it. If a unit ever misbehaves, this is the first thing to disable.
     */
    private fun maybeAutoSendBindAck() {
        val locked = _reportedTelemetry.value.locked
        val bound = _reportedTelemetry.value.bluetoothBound
        val key = locked to bound
        if (key == lastAutoBindAckFor) return // already handled this combo

        val ackValue = when {
            locked == false && bound == true -> 2
            locked == true && bound == false -> 1
            else -> null
        }
        if (ackValue != null) {
            lastAutoBindAckFor = key
            sendCommand(ProtocolCodec.CODE_BIND_ACK, listOf(ackValue))
        }
    }

    /**
     * Asks the scooter for its actual current max speed / torque / brake settings
     * (data=[0] on each of codes 60-63, same as the stock app's tuning screen). Responses
     * land in telemetry asynchronously. Spaced out so a slow controller isn't hit with four
     * frames in the same connection event.
     */
    fun queryCurrentConfig() {
        listOf(
            ProtocolCodec.CODE_SET_MAX_SPEED,
            ProtocolCodec.CODE_SET_START_TORQUE,
            ProtocolCodec.CODE_SET_MAX_TORQUE,
            ProtocolCodec.CODE_SET_BRAKE_STRENGTH
        ).forEachIndexed { i, code ->
            mainHandler.postDelayed({
                if (_connectionState.value == ConnectionState.READY) sendCommand(code, listOf(0))
            }, i * CONFIG_QUERY_SPACING_MS)
        }
    }

    // ---------------- Convenience wrappers for the UI layer ----------------

    fun toggleLock(currentlyLocked: Boolean) =
        sendStateful(ProtocolCodec.CODE_LOCK_TOGGLE, currentlyLocked, ProtocolCodec.lockValue(currentlyLocked))

    /** Gear 1-3 (code 66). Higher gears 4/5 can be reported but not selected, as in the stock app. */
    fun setGear(gear: Int): Boolean {
        val g = gear.coerceIn(1, 3)
        // The scooter lists the gears it actually has (version frame); the stock app skips the rest.
        val available = _telemetry.value.availableGears
        if (available != null && g !in available) {
            postMessage("Gear $g isn't available on this scooter.")
            return false
        }
        return sendAbsolute(ProtocolCodec.CODE_SET_GEAR, g, "Gear")
    }

    /** Speed unit (code 67): false = km/h, true = mph. Stored on the scooter and reported in the status frame. */
    fun setSpeedUnit(miles: Boolean) = sendAbsolute(
        ProtocolCodec.CODE_SPEED_UNIT,
        if (miles) ProtocolCodec.UNIT_MILE_VALUE else ProtocolCodec.UNIT_KM_VALUE,
        "Speed unit"
    )

    /** Dashboard work mode 1-3 (code 74). Reply layout is unconfirmed, so check the Debug log if the chips don't react. */
    fun setWorkMode(mode: Int) = sendAbsolute(ProtocolCodec.CODE_WORK_MODE, mode.coerceIn(1, 3), "Work mode")

    /** Read-only: asks the dashboard for its current mode (data [0], as the stock app does on open). */
    fun queryWorkMode() = sendCommand(ProtocolCodec.CODE_WORK_MODE, listOf(0))

    /** Read-only version query (code 171 with [173]); the reply lands in telemetry hardware/softwareVersion. */
    fun queryVersions() = sendCommand(ProtocolCodec.CODE_VERSION_QUERY, listOf(ProtocolCodec.VERSION_QUERY_VALUE))

    fun findMe(activate: Boolean) =
        sendCommand(ProtocolCodec.CODE_FIND_ME, listOf(if (activate) 1 else 2))

    // Headlight / cruise / kick-start: the byte sent depends on the CURRENT state
    // (on -> 1, off -> 2). See ProtocolCodec.switchValue() for why -- getting this backwards
    // was the reason these three "beeped but did nothing".
    fun toggleHeadlight(currentlyOn: Boolean) = sendSwitch(ProtocolCodec.CODE_HEADLIGHT, currentlyOn)

    fun toggleCruise(currentlyOn: Boolean) = sendSwitch(ProtocolCodec.CODE_CRUISE, currentlyOn)

    fun toggleStartupMode(currentlyOn: Boolean) = sendSwitch(ProtocolCodec.CODE_STARTUP_MODE, currentlyOn)

    /**
     * Sends a tuning write (codes 60-63) and then checks the scooter answered it. The stock app
     * waits 1s for the reply and reverts its UI (and reports failure) if none comes. We do the
     * equivalent: if nothing arrives in time, tell the user and re-read the real value so the
     * sliders never keep showing a setting the scooter didn't accept.
     */
    private fun writeConfig(code: Int, value: Int, label: String): Boolean {
        val sentAt = System.currentTimeMillis()
        if (!sendCommand(code, listOf(value))) return false
        mainHandler.postDelayed({
            if (_connectionState.value != ConnectionState.READY) return@postDelayed
            val answered = (lastRxAt[code] ?: 0L) >= sentAt
            if (!answered) {
                frameLog.add(LogDirection.INFO, "no reply to code $code within ${CONFIG_CONFIRM_MS}ms")
                postMessage("$label: scooter didn't confirm -- reloading its real value.")
                sendCommand(code, listOf(0)) // query current value; telemetry resyncs the slider
            }
        }, CONFIG_CONFIRM_MS)
        return true
    }

    fun setMaxSpeed(kmh: Int): Boolean {
        val value = kmh.coerceIn(1, 31)
        if (_demoMode.value) {
            updateDemoTelemetry { it.copy(maxSpeedLimit = value, configRevision = it.configRevision + 1) }
            return true
        }
        return writeConfig(ProtocolCodec.CODE_SET_MAX_SPEED, value, "Max speed")
    }

    // The 1-10 / 0-9 values shown in the UI are NOT sent raw -- they're rescaled onto a wider
    // ~20-200 range first (confirmed from DIYPerformanceTuning.vue). See ProtocolCodec.
    fun setStartingTorque(value: Int): Boolean {
        val display = value.coerceIn(1, 10)
        if (_demoMode.value) {
            updateDemoTelemetry {
                it.copy(
                    startingTorque = display,
                    rawStartingTorque = ProtocolCodec.torqueWriteByte(display),
                    configRevision = it.configRevision + 1
                )
            }
            return true
        }
        return writeConfig(ProtocolCodec.CODE_SET_START_TORQUE, ProtocolCodec.torqueWriteByte(display), "Starting torque")
    }

    fun setMaxDrivingTorque(value: Int): Boolean {
        val display = value.coerceIn(1, 10)
        if (_demoMode.value) {
            updateDemoTelemetry {
                it.copy(
                    maxDrivingTorque = display,
                    rawMaxDrivingTorque = ProtocolCodec.torqueWriteByte(display),
                    configRevision = it.configRevision + 1
                )
            }
            return true
        }
        return writeConfig(ProtocolCodec.CODE_SET_MAX_TORQUE, ProtocolCodec.torqueWriteByte(display), "Max torque")
    }

    fun setBrakeStrength(value: Int): Boolean {
        val display = value.coerceIn(0, 9)
        if (_demoMode.value) {
            updateDemoTelemetry {
                it.copy(
                    brakeStrength = display,
                    rawBrakeStrength = ProtocolCodec.brakeWriteByte(display),
                    configRevision = it.configRevision + 1
                )
            }
            return true
        }
        return writeConfig(ProtocolCodec.CODE_SET_BRAKE_STRENGTH, ProtocolCodec.brakeWriteByte(display), "Brake strength")
    }

    /**
     * The stock app's "restore defaults": data=[255] to codes 60, 61, 62, 63 in turn (it waits for
     * each reply before sending the next), then the settings are re-read. Spaced rather than
     * reply-gated; the re-read at the end shows what the scooter actually ended up with.
     */
    fun resetTuningToDefaults() {
        if (_demoMode.value) {
            updateDemoTelemetry {
                it.copy(
                    maxSpeedLimit = 25,
                    startingTorque = 5,
                    maxDrivingTorque = 7,
                    brakeStrength = 4,
                    rawStartingTorque = ProtocolCodec.torqueWriteByte(5),
                    rawMaxDrivingTorque = ProtocolCodec.torqueWriteByte(7),
                    rawBrakeStrength = ProtocolCodec.brakeWriteByte(4),
                    configRevision = it.configRevision + 1
                )
            }
            postMessage("Demo tuning reset to defaults.")
            return
        }
        frameLog.add(LogDirection.INFO, "restoring tuning defaults (255 to codes 60-63)")
        listOf(
            ProtocolCodec.CODE_SET_MAX_SPEED,
            ProtocolCodec.CODE_SET_START_TORQUE,
            ProtocolCodec.CODE_SET_MAX_TORQUE,
            ProtocolCodec.CODE_SET_BRAKE_STRENGTH
        ).forEachIndexed { i, code ->
            mainHandler.postDelayed({
                if (_connectionState.value == ConnectionState.READY)
                    sendCommand(code, listOf(ProtocolCodec.RESET_TO_DEFAULT_VALUE))
            }, i * 400L)
        }
        mainHandler.postDelayed({
            if (_connectionState.value == ConnectionState.READY) queryCurrentConfig()
        }, 4 * 400L + 600L)
    }

    // IMPORTANT: unlike the main lock (code 51), the seat/helmet/storage/brake codes (55-58) in the
    // real app are NOT driven by a real tracked lock state -- they're triggered from a local
    // ephemeral UI flag. No confirmed telemetry field reports these compartments' real state, so
    // treat these as "pulse" actions until verified against hardware.
    private var seatToggle = false
    private var helmetToggle = false
    private var storageToggle = false
    private var brakeToggle = false

    fun pulseSeatLock() { seatToggle = !seatToggle; sendCommand(ProtocolCodec.CODE_SEAT_LOCK, listOf(if (seatToggle) 1 else 2)) }
    fun pulseHelmetLock() { helmetToggle = !helmetToggle; sendCommand(ProtocolCodec.CODE_HELMET_LOCK, listOf(if (helmetToggle) 1 else 2)) }
    fun pulseStorageLock() { storageToggle = !storageToggle; sendCommand(ProtocolCodec.CODE_STORAGE_LOCK, listOf(if (storageToggle) 1 else 2)) }
    fun pulseBrakeLock() { brakeToggle = !brakeToggle; sendCommand(ProtocolCodec.CODE_BRAKE_LOCK, listOf(if (brakeToggle) 1 else 2)) }

    /** mode: 1=off, 2=horse pattern, 3=banner pattern, 4=purity, 5=custom color (pass color) */
    fun setAmbientLampMode(mode: Int, color: ProtocolCodec.AmbientColor? = null) {
        val hue = if (mode == 5) (color?.hue ?: 0) else 0
        sendCommand(ProtocolCodec.CODE_AMBIENT_LAMP, listOf(mode.coerceIn(1, 5), hue))
    }

    private fun updateDemoTelemetry(transform: (ScooterTelemetry) -> ScooterTelemetry) {
        val updated = transform(_reportedTelemetry.value)
        _reportedTelemetry.value = updated
        _telemetry.value = updated
    }

    private fun demoTelemetry() = ScooterTelemetry(
        voltage = 48.6,
        current = 6,
        speedKmh = 18.4,
        batteryPercent = 82,
        controllerTempC = 34,
        motorTempC = 39,
        batteryTempC = 30,
        motorSpeed = 612,
        locked = false,
        bluetoothBound = true,
        cruiseOn = false,
        headlightOn = true,
        ambientLightOn = true,
        startupModeOn = false,
        gear = 2,
        speedUnitMiles = false,
        singleTripKm = 4.7,
        totalDistanceKm = 1284,
        instrumentHardwareVersion = 3,
        instrumentSoftwareVersion = 18,
        controllerHardwareVersion = 2,
        controllerSoftwareVersion = 27,
        availableGears = setOf(1, 2, 3),
        throttle = 0,
        brake1 = 0,
        brake2 = 0,
        throttleRaw = 786,
        brake1Raw = 0,
        brake2Raw = 0,
        bms = BmsInfo(
            id = 1,
            voltage = 48.6,
            current = 3.2,
            cycles = 142,
            ratedCapacity = 15000,
            remainingCapacity = 12300,
            temperature = 30,
            charging = false,
            discharging = true,
            lowVoltageProtection = false,
            overVoltageProtection = false,
            anomaly = false
        ),
        workMode = 2,
        maxSpeedLimit = 25,
        startingTorque = 5,
        maxDrivingTorque = 7,
        brakeStrength = 4,
        rawStartingTorque = ProtocolCodec.torqueWriteByte(5),
        rawMaxDrivingTorque = ProtocolCodec.torqueWriteByte(7),
        rawBrakeStrength = ProtocolCodec.brakeWriteByte(4)
    )

    private fun applyDemoCommand(code: Int, data: List<Int>) {
        val value = data.firstOrNull()
        lastRxAt[code] = System.currentTimeMillis()
        frameLog.add(LogDirection.TX, "DEMO code=$code data=$data (simulated)")
        updateDemoTelemetry { t ->
            when (code) {
                ProtocolCodec.CODE_LOCK_TOGGLE -> t.copy(locked = value == ProtocolCodec.LOCK_LOCK_VALUE)
                ProtocolCodec.CODE_HEADLIGHT -> t.copy(headlightOn = value == ProtocolCodec.SWITCH_ON_VALUE)
                ProtocolCodec.CODE_CRUISE -> t.copy(cruiseOn = value == ProtocolCodec.SWITCH_ON_VALUE)
                ProtocolCodec.CODE_STARTUP_MODE -> t.copy(startupModeOn = value == ProtocolCodec.SWITCH_ON_VALUE)
                ProtocolCodec.CODE_SET_GEAR -> value?.takeIf { it in 1..3 }?.let { t.copy(gear = it) } ?: t
                ProtocolCodec.CODE_SPEED_UNIT -> value?.let { t.copy(speedUnitMiles = it == ProtocolCodec.UNIT_MILE_VALUE) } ?: t
                ProtocolCodec.CODE_WORK_MODE -> value?.takeIf { it in 1..3 }?.let { t.copy(workMode = it) } ?: t
                ProtocolCodec.CODE_AMBIENT_LAMP -> t.copy(ambientLightOn = value != 1)
                ProtocolCodec.CODE_FIND_ME -> t
                ProtocolCodec.CODE_VERSION_QUERY -> t
                else -> t
            }
        }
        if (code == ProtocolCodec.CODE_FIND_ME) {
            postMessage(if (value == 1) "Demo scooter would flash/beep now." else "Demo find-me stopped.")
        }
    }

    companion object {
        const val DEMO_ADDRESS = "DE:MO:00:00:00:01"
    }
}
