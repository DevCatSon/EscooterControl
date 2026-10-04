package com.devcat.escootercontrol.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcat.escootercontrol.ble.ConnectionState
import com.devcat.escootercontrol.ble.DiscoveredDevice
import com.devcat.escootercontrol.ble.DashboardTelemetry
import com.devcat.escootercontrol.ble.ScooterBleManager
import com.devcat.escootercontrol.ble.ScooterTelemetry
import com.devcat.escootercontrol.ble.toDashboardTelemetry
import com.devcat.escootercontrol.data.FeatureFlags
import com.devcat.escootercontrol.data.FeatureSupportStore
import com.devcat.escootercontrol.data.SavedScooter
import com.devcat.escootercontrol.data.ScooterStore
import kotlinx.coroutines.launch
import com.devcat.escootercontrol.ble.FrameLog
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ScooterViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = ScooterBleManager(application)
    private val featureStore = FeatureSupportStore(application)
    private val scooterStore = ScooterStore(application)
    private var autoReconnectTried = false

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val telemetry: StateFlow<ScooterTelemetry> = bleManager.telemetry
    val dashboardTelemetry: StateFlow<DashboardTelemetry> = telemetry
        .map { it.toDashboardTelemetry() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            telemetry.value.toDashboardTelemetry()
        )
    // Lock changes are rare, so expose them separately from high-rate telemetry.
    // App-wide lock UI can observe this without making the NavHost recompose for
    // every speed/battery/status frame.
    val locked: StateFlow<Boolean?> = telemetry
        .map { it.locked }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, telemetry.value.locked)
    val scanResults: StateFlow<List<DiscoveredDevice>> = bleManager.scanResults
    val scanError: StateFlow<String?> = bleManager.scanError
    val featureFlags: StateFlow<FeatureFlags> = featureStore.flags
    val messages: SharedFlow<String> = bleManager.messages
    val frameLog: FrameLog = bleManager.frameLog
    val lastScooter: StateFlow<SavedScooter?> = scooterStore.last
    val nicknames: StateFlow<Map<String, String>> = scooterStore.nicknames
    val connectedAddress: StateFlow<String?> = bleManager.connectedAddress
    val connectedName: StateFlow<String?> = bleManager.connectedName
    val demoMode: StateFlow<Boolean> = bleManager.demoMode

    init {
        // Remember the scooter once a connection actually works (not merely attempted).
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.READY && !bleManager.demoMode.value) {
                    val address = bleManager.connectedAddress.value
                    if (address != null) scooterStore.saveLast(address, bleManager.connectedName.value ?: "Scooter")
                }
            }
        }
    }

    fun setFeatureSupported(update: (FeatureFlags) -> FeatureFlags) = featureStore.update(update)

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    fun queryCurrentConfig() = bleManager.queryCurrentConfig()
    fun resetTuningToDefaults() = bleManager.resetTuningToDefaults()

    fun connect(device: BluetoothDevice, name: String? = null) = bleManager.connect(device, name)

    /** Connects to the saved scooter without scanning. False if none is saved or Bluetooth is off. */
    fun reconnectLast(): Boolean {
        val last = scooterStore.last.value ?: return false
        return bleManager.connectToAddress(last.address, last.name)
    }

    /** Called once per app launch after permissions are granted. */
    fun autoReconnectOnce() {
        if (autoReconnectTried) return
        autoReconnectTried = true
        if (connectionState.value == ConnectionState.DISCONNECTED) reconnectLast()
    }

    fun cancelConnect() = bleManager.disconnect()
    fun enterDemoMode(name: String = "Demo Scooter") = bleManager.enterDemoMode(name)

    fun renameScooter(address: String, name: String) = scooterStore.setNickname(address, name)
    fun forgetSavedScooter() {
        // Forget is intentionally stronger than Disconnect: remove the auto-reconnect target,
        // then end the live session so the user immediately sees that the action happened.
        scooterStore.forgetLast()
        autoReconnectTried = true
        bleManager.postMessage("Scooter forgotten. Auto-reconnect is off until you connect again.")
        bleManager.disconnect()
    }
    fun disconnect() = bleManager.disconnect()

    fun toggleLock() {
        val locked = telemetry.value.locked
        if (locked == null) { bleManager.postMessage("Waiting for scooter status..."); return }
        bleManager.toggleLock(locked)
    }

    fun setSpeedUnit(miles: Boolean) = bleManager.setSpeedUnit(miles)
    fun setGear(gear: Int) = bleManager.setGear(gear)
    fun queryVersions() = bleManager.queryVersions()
    fun setWorkMode(mode: Int) = bleManager.setWorkMode(mode)
    fun queryWorkMode() = bleManager.queryWorkMode()

    fun sendRaw(code: Int, data: List<Int>) = bleManager.sendRaw(code, data)

    fun findMe(activate: Boolean) = bleManager.findMe(activate)

    fun setMaxSpeed(kmh: Int) = bleManager.setMaxSpeed(kmh)
    fun setStartingTorque(v: Int) = bleManager.setStartingTorque(v)
    fun setMaxDrivingTorque(v: Int) = bleManager.setMaxDrivingTorque(v)
    fun setBrakeStrength(v: Int) = bleManager.setBrakeStrength(v)

    // These three send a byte derived from the CURRENT state, so they must not fire before the
    // first status frame has told us what that state is.
    fun toggleCruise() {
        val on = telemetry.value.cruiseOn ?: return waitForStatus()
        bleManager.toggleCruise(on)
    }
    fun toggleHeadlight() {
        val on = telemetry.value.headlightOn ?: return waitForStatus()
        bleManager.toggleHeadlight(on)
    }
    fun toggleStartupMode() {
        val on = telemetry.value.startupModeOn ?: return waitForStatus()
        bleManager.toggleStartupMode(on)
    }
    private fun waitForStatus() = bleManager.postMessage("Waiting for scooter status...")

    fun setAmbientLampMode(mode: Int, color: com.devcat.escootercontrol.ble.ProtocolCodec.AmbientColor? = null) =
        bleManager.setAmbientLampMode(mode, color)

    fun pulseSeatLock() = bleManager.pulseSeatLock()
    fun pulseHelmetLock() = bleManager.pulseHelmetLock()
    fun pulseStorageLock() = bleManager.pulseStorageLock()
    fun pulseBrakeLock() = bleManager.pulseBrakeLock()

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}
