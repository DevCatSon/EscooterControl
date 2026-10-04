package com.devcat.escootercontrol.ble

import androidx.compose.runtime.Immutable

/**
 * Holds decoded scooter telemetry. Fields default to null/0 until their
 * corresponding opcode has been received at least once.
 *
 * Field layout follows the byte offsets reverse engineered from
 * updateBleDeviceInfo() in app-service.js -- see the protocol spec doc.
 */
@Immutable
data class ScooterTelemetry(
    // CODE 16 - motor/battery live values
    val voltage: Double? = null,
    val current: Int? = null,
    val speedKmh: Double? = null,
    val batteryPercent: Int? = null,
    val controllerTempC: Int? = null,
    val motorTempC: Int? = null,
    val batteryTempC: Int? = null,
    val motorSpeed: Int? = null,

    // CODE 17 - status flags / odometer
    val locked: Boolean? = null,          // lockCarState
    val bluetoothBound: Boolean? = null,  // bluetoothBindingStatus
    val cruiseOn: Boolean? = null,
    val headlightOn: Boolean? = null,     // frontControllerState
    val ambientLightOn: Boolean? = null,  // ambientLightingState (on/off only -- mode/color aren't reported back)
    val startupModeOn: Boolean? = null,
    val gear: Int? = null,                // gearsVal 1..5 as reported (see ProtocolCodec.uiGear)
    val speedUnitMiles: Boolean? = null,  // status byte0 bit7: false = km, true = mile   // startingModeState -- exact meaning of the two states unconfirmed
    val singleTripKm: Double? = null,
    val totalDistanceKm: Int? = null,

    // CODE 18 (streams ~1 Hz) and the CODE 171 reply: version info. "Instrument" is the dashboard/
    // display unit, as in the stock app's own log text.
    val instrumentHardwareVersion: Int? = null,
    val instrumentSoftwareVersion: Int? = null,
    val controllerHardwareVersion: Int? = null,
    val controllerSoftwareVersion: Int? = null,
    // Gears the scooter says it has (CODE 18 last byte, bit0 = gear 1 ... bit6 = gear 7).
    val availableGears: Set<Int>? = null,

    // CODE 19: throttle / brake inputs, six 16-bit values. The "raw" trio is the stock app's *_CY
    // fields; on a live scooter the throttle raw value idles around 0x0312 while the first trio is 0.
    val throttle: Int? = null,
    val brake1: Int? = null,
    val brake2: Int? = null,
    val throttleRaw: Int? = null,
    val brake1Raw: Int? = null,
    val brake2Raw: Int? = null,

    // CODE 31: battery pack (BMS). Not every scooter sends it.
    val bms: BmsInfo? = null,

    // CODE 74: the dashboard's work mode (1-3). Reply layout not yet confirmed on hardware.
    val workMode: Int? = null,

    // CODE 60/61/62/63 last-known config (populated from ack, not telemetry directly)
    val maxSpeedLimit: Int? = null,
    val startingTorque: Int? = null,
    val maxDrivingTorque: Int? = null,
    val brakeStrength: Int? = null,

    // Raw bytes exactly as the scooter reported them (before display scaling). Kept so the
    // write-scale vs read-scale question in the protocol spec can be settled with real data.
    val rawStartingTorque: Int? = null,
    val rawMaxDrivingTorque: Int? = null,
    val rawBrakeStrength: Int? = null,

    // Bumped on every reply to a tuning read/write (codes 60-63), even when the decoded value is
    // unchanged, so the UI can resync sliders after a rejected write.
    val configRevision: Int = 0,

    // Sync state
    val lastZt: Int = 0x5A // fallback matches the app's own hardcoded initial handshake
)

/** Battery pack data from CODE 31, laid out as in the stock app (see the decoder for offsets). */
/**
 * Small dashboard-only projection of telemetry.
 *
 * The BLE layer updates [ScooterTelemetry] for many frames the dashboard does not display
 * (sensor raw values, BMS details, tuning replies, sync bytes). Exposing this projection through
 * a distinct StateFlow prevents those unrelated frames from recomposing the whole dashboard.
 */
@Immutable
data class DashboardTelemetry(
    val speedKmh: Double? = null,
    val speedUnitMiles: Boolean? = null,
    val batteryPercent: Int? = null,
    val voltage: Double? = null,
    val motorTempC: Int? = null,
    val headlightOn: Boolean? = null,
    val cruiseOn: Boolean? = null,
    val startupModeOn: Boolean? = null,
    val gear: Int? = null,
    val availableGears: Set<Int>? = null,
    val locked: Boolean? = null,
    val totalDistanceKm: Int? = null
)

fun ScooterTelemetry.toDashboardTelemetry() = DashboardTelemetry(
    speedKmh = speedKmh,
    speedUnitMiles = speedUnitMiles,
    batteryPercent = batteryPercent,
    voltage = voltage,
    motorTempC = motorTempC,
    headlightOn = headlightOn,
    cruiseOn = cruiseOn,
    startupModeOn = startupModeOn,
    gear = gear,
    availableGears = availableGears,
    locked = locked,
    totalDistanceKm = totalDistanceKm
)

@Immutable
data class BmsInfo(
    val id: Int,
    val voltage: Double,
    val current: Double,
    val cycles: Int?,
    val ratedCapacity: Int?,
    val remainingCapacity: Int?,
    val temperature: Int?,
    val charging: Boolean,
    val discharging: Boolean,
    val lowVoltageProtection: Boolean,
    val overVoltageProtection: Boolean,
    val anomaly: Boolean
)

object TelemetryDecoder {

    /** data[1] when the reply has LEN == 2: the scooter's own scale for this setting. */
    private fun reportedMax(frame: ProtocolCodec.IncomingFrame): Int? =
        if (frame.len == 2) frame.data.getOrNull(1)?.toInt()?.and(0xFF) else null

    /**
     * Applies an incoming frame to the previous telemetry snapshot, returning
     * an updated copy. Unknown/unhandled opcodes just update lastZt.
     */
    fun apply(prev: ScooterTelemetry, frame: ProtocolCodec.IncomingFrame): ScooterTelemetry {
        val hex = ProtocolCodec.bytesToHex(frame.data)
        var t = prev.copy(lastZt = frame.zt)
        if (frame.code in 60..63) t = t.copy(configRevision = prev.configRevision + 1)

        // hex here is DATA only (post header) -- offsets below are re-based
        // from the original spec's whole-frame offsets (which start at byte 3)
        // by subtracting the 3-byte header (zt,code,len) => 6 hex chars.
        fun sub(offsetInFullFrame: Int, lenChars: Int): String {
            val o = offsetInFullFrame - 6
            if (o < 0 || o + lenChars > hex.length) return ""
            return hex.substring(o, o + lenChars)
        }
        fun hexInt(offsetInFullFrame: Int, lenChars: Int): Int? =
            sub(offsetInFullFrame, lenChars).takeIf { it.isNotEmpty() }?.toIntOrNull(16)

        when (frame.code) {
            ProtocolCodec.CODE_TELEMETRY_MOTOR -> {
                t = t.copy(
                    voltage = hexInt(6, 4)?.let { it * 0.01 },
                    current = hexInt(10, 4),
                    speedKmh = hexInt(14, 4)?.let { it * 0.1 },
                    batteryPercent = hexInt(18, 2),
                    controllerTempC = hexInt(20, 2),
                    motorTempC = hexInt(22, 2),
                    batteryTempC = hexInt(24, 2),
                    motorSpeed = hexInt(26, 4)
                )
            }
            ProtocolCodec.CODE_TELEMETRY_STATUS -> {
                // byte at full-frame offset 8 (hex chars 8,2) holds several 1-bit flags;
                // bit layout from source: bit0=switchcontrol, bit1=lockCarState,
                // bit2=horn, bit3=leftTurn, bit4=rightTurn, bit5=ambientLight,
                // bit6=bluetoothBindingStatus (LSB-first, matches original .substr(-N,1) reads)
                val flagsByte = hexInt(8, 2)
                val locked = flagsByte?.let { (it shr 1) and 0x1 == 1 }
                val btBound = flagsByte?.let { (it shr 6) and 0x1 == 1 }
                val ambientOn = flagsByte?.let { (it shr 5) and 0x1 == 1 }

                // byte at offset 6: bits0-2=gearsVal, bit3=frontController(headlight), bit4=taillight,
                // bit5=startingMode, bit6=cruiseControlState, bit7=speedUnit
                val gearFlagsByte = hexInt(6, 2)
                val cruise = gearFlagsByte?.let { (it shr 6) and 0x1 == 1 }
                val headlight = gearFlagsByte?.let { (it shr 3) and 0x1 == 1 }
                val startupMode = gearFlagsByte?.let { (it shr 5) and 0x1 == 1 }
                val gear = gearFlagsByte?.let { it and 0x7 }
                val unitMiles = gearFlagsByte?.let { (it shr 7) and 0x1 == 1 }

                t = t.copy(
                    locked = locked ?: t.locked,
                    bluetoothBound = btBound ?: t.bluetoothBound,
                    cruiseOn = cruise ?: t.cruiseOn,
                    headlightOn = headlight ?: t.headlightOn,
                    ambientLightOn = ambientOn ?: t.ambientLightOn,
                    startupModeOn = startupMode ?: t.startupModeOn,
                    gear = gear ?: t.gear,
                    speedUnitMiles = unitMiles ?: t.speedUnitMiles,
                    singleTripKm = hexInt(10, 4)?.let { it / 100.0 },
                    totalDistanceKm = hexInt(14, 4)
                )
            }
            // Response to a config query/set on codes 60-63. The real app reads this
            // same field (first data byte) both to confirm a write AND to read the
            // live current value when data=[0] was sent as a query -- so this single
            // handler covers both "what's it set to right now" and "did my write take."
            ProtocolCodec.CODE_SET_MAX_SPEED -> {
                frame.data.getOrNull(0)?.let { raw ->
                    val v = raw.toInt() and 0xFF
                    t = t.copy(maxSpeedLimit = v)
                }
            }
            ProtocolCodec.CODE_SET_START_TORQUE -> {
                frame.data.getOrNull(0)?.let { b ->
                    val raw = b.toInt() and 0xFF
                    val max = reportedMax(frame)
                    t = t.copy(rawStartingTorque = raw, startingTorque = ProtocolCodec.startTorqueDisplay(raw, max))
                }
            }
            ProtocolCodec.CODE_SET_MAX_TORQUE -> {
                frame.data.getOrNull(0)?.let { b ->
                    val raw = b.toInt() and 0xFF
                    val max = reportedMax(frame)
                    t = t.copy(rawMaxDrivingTorque = raw, maxDrivingTorque = ProtocolCodec.maxTorqueDisplay(raw, max))
                }
            }
            ProtocolCodec.CODE_SET_BRAKE_STRENGTH -> {
                frame.data.getOrNull(0)?.let { b ->
                    val raw = b.toInt() and 0xFF
                    val max = reportedMax(frame)
                    t = t.copy(rawBrakeStrength = raw, brakeStrength = ProtocolCodec.brakeDisplay(raw, max))
                }
            }
            // Reply to the version query (send 171 [173]): data[0] = instrument hardware,
            // data[1] = instrument software -- the same pair CODE 18 already streams.
            ProtocolCodec.CODE_VERSION_QUERY -> {
                val hw = frame.data.getOrNull(0)?.toInt()?.and(0xFF)
                val sw = frame.data.getOrNull(1)?.toInt()?.and(0xFF)
                t = t.copy(
                    instrumentHardwareVersion = hw ?: t.instrumentHardwareVersion,
                    instrumentSoftwareVersion = sw ?: t.instrumentSoftwareVersion
                )
            }
            // Version frame, layout from the stock app (full-frame hex offsets):
            //  6,4 instrument unit id | 10,2 instr HW | 12,2 instr SW | 14,4 controller unit id |
            //  18,2 controller HW | 20,2 controller SW | 22,2 gear-availability bitmask
            ProtocolCodec.CODE_TELEMETRY_VERSION -> {
                val gearMask = hexInt(22, 2)
                t = t.copy(
                    instrumentHardwareVersion = hexInt(10, 2) ?: t.instrumentHardwareVersion,
                    instrumentSoftwareVersion = hexInt(12, 2) ?: t.instrumentSoftwareVersion,
                    controllerHardwareVersion = hexInt(18, 2) ?: t.controllerHardwareVersion,
                    controllerSoftwareVersion = hexInt(20, 2) ?: t.controllerSoftwareVersion,
                    availableGears = gearMask?.let { m -> (1..7).filter { (m shr (it - 1)) and 1 == 1 }.toSet() }
                        ?: t.availableGears
                )
            }
            // Throttle / brake inputs (full-frame hex offsets 6,10,14,18,22,26; 4 chars each).
            ProtocolCodec.CODE_TELEMETRY_SENSORS -> {
                t = t.copy(
                    throttle = hexInt(6, 4) ?: t.throttle,
                    brake1 = hexInt(10, 4) ?: t.brake1,
                    brake2 = hexInt(14, 4) ?: t.brake2,
                    throttleRaw = hexInt(18, 4) ?: t.throttleRaw,
                    brake1Raw = hexInt(22, 4) ?: t.brake1Raw,
                    brake2Raw = hexInt(26, 4) ?: t.brake2Raw
                )
            }
            // Battery pack. Offsets from the stock app: id 6,4 | flags 8,2 (overlaps the id's low byte,
            // exactly as the original reads it) | voltage 10,4 *0.01 | current 14,4 *0.01 | cycles 18,4 |
            // rated capacity 22,4 | remaining capacity 26,4 | temperature 30,4.
            ProtocolCodec.CODE_TELEMETRY_BMS -> {
                val id = hexInt(6, 4)
                val flags = hexInt(8, 2)
                val v = hexInt(10, 4)
                val a = hexInt(14, 4)
                if (id != null && flags != null && v != null && a != null) {
                    t = t.copy(
                        bms = BmsInfo(
                            id = id,
                            voltage = v * 0.01,
                            current = a * 0.01,
                            cycles = hexInt(18, 4),
                            ratedCapacity = hexInt(22, 4),
                            remainingCapacity = hexInt(26, 4),
                            temperature = hexInt(30, 4),
                            charging = flags and 0x01 != 0,
                            discharging = flags and 0x02 != 0,
                            lowVoltageProtection = flags and 0x04 != 0,
                            overVoltageProtection = flags and 0x08 != 0,
                            anomaly = flags and 0x10 != 0
                        )
                    )
                }
            }
            // Work mode reply. The stock app reads the byte third from the END of the whole frame,
            // i.e. the second-to-last data byte when the reply has two or more; a one-byte reply is
            // just the echo of what we wrote. Only 1..3 are valid, so a query echo of 0 is ignored.
            ProtocolCodec.CODE_WORK_MODE -> {
                val idx = if (frame.data.size >= 2) frame.data.size - 2 else 0
                val v = frame.data.getOrNull(idx)?.toInt()?.and(0xFF)
                if (v != null && v in 1..3) t = t.copy(workMode = v)
            }
            // intentionally not decoded yet in this app -- add here following the
            // same offset pattern documented in the protocol spec if you need them.
        }
        return t
    }
}
