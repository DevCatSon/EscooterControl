package com.devcat.escootercontrol.ble

/**
 * Implements the reverse-engineered Vicont scooter BLE frame protocol.
 * See vicont-ble-protocol-spec.md for the full writeup this was derived from.
 *
 * Outgoing frame:  FA AF A5 | ZT | CODE | LEN | DATA[LEN] | CHECKSUM
 * Incoming frame:        ZT | CODE | LEN | DATA[LEN] | CHECKSUM   (no magic prefix)
 *
 * ZT is not a secret/key -- it's just the first byte of the last frame received
 * from the scooter, echoed back on the next outgoing command. There is no real
 * cryptographic authentication in this protocol.
 *
 * This file is deliberately free of Android imports so it can be unit-tested on a
 * plain JVM.
 */
object ProtocolCodec {

    private val MAGIC = byteArrayOf(0xFA.toByte(), 0xAF.toByte(), 0xA5.toByte())

    // ---- Outgoing command opcodes ----
    const val CODE_LOCK_TOGGLE = 51        // data=[1] unlock, data=[2] lock
    const val CODE_FIND_ME = 52            // data=[1] on, data=[2] off
    const val CODE_STARTUP_MODE = 53       // engage/release toggle, see switchValue()
    const val CODE_CRUISE = 54             // engage/release toggle, see switchValue()
    const val CODE_SEAT_LOCK = 55          // data=[1]/[2]
    const val CODE_HELMET_LOCK = 56        // data=[1]/[2]
    const val CODE_STORAGE_LOCK = 57       // data=[1]/[2]
    const val CODE_BRAKE_LOCK = 58         // data=[1]/[2]
    const val CODE_BIND_ACK = 76           // data=[1] or [2]
    const val CODE_SET_MAX_SPEED = 60      // speed configuration
    const val CODE_SET_START_TORQUE = 61   // data=[scaled 20..200], see torqueWriteByte()
    const val CODE_SET_MAX_TORQUE = 62     // data=[scaled 20..200], see torqueWriteByte()
    const val CODE_SET_BRAKE_STRENGTH = 63 // data=[scaled 1..200], see brakeWriteByte()
    const val CODE_AMBIENT_LAMP = 65       // data=[mode(1-5), hue] -- hue only used for mode 5, see spec
    const val CODE_HEADLIGHT = 69          // engage/release toggle, see switchValue()
    const val CODE_SET_GEAR = 66           // data=[1|2|3]; see uiGear() for how 4/5 read back
    const val CODE_SPEED_UNIT = 67         // data=[1] km, [2] mile (absolute); status byte0 bit7 reports it
    const val CODE_WORK_MODE = 74          // query with [0]; set with [1|2|3] (the dashboard's own mode register)
    const val CODE_VERSION_QUERY = 171     // send [173]; reply data=[hardware ver, software ver]

    // ---- Incoming telemetry opcodes ----
    const val CODE_TELEMETRY_MOTOR = 16
    const val CODE_TELEMETRY_STATUS = 17
    const val CODE_TELEMETRY_VERSION = 18
    const val CODE_TELEMETRY_SENSORS = 19
    const val CODE_TELEMETRY_BMS = 31

    const val VERSION_QUERY_VALUE = 173
    const val UNIT_KM_VALUE = 1
    const val UNIT_MILE_VALUE = 2

    const val LOCK_UNLOCK_VALUE = 1
    const val LOCK_LOCK_VALUE = 2

    /** data byte meaning "release / turn OFF" for the 53/54/69 toggles (same sense as unlock=1). */
    const val SWITCH_OFF_VALUE = 1
    /** data byte meaning "engage / turn ON" for the 53/54/69 toggles (same sense as lock=2). */
    const val SWITCH_ON_VALUE = 2

    /** The two fixed frames the stock app sends ~1s after notifications are enabled. Sent verbatim. */
    val HANDSHAKE_FRAMES: List<ByteArray> = listOf(
        hexToBytes("FAAFA55A01005B"),
        hexToBytes("FAAFA5FA01005B")
    )

    /**
     * Data byte for the headlight (69), cruise (54) and startup-mode (53) toggles.
     *
     * Confirmed from the stock app's sendSwitch(): for every code except 51/52 it sends
     * `[1 == currentState ? 1 : 2]`, i.e. the CURRENT state is passed through:
     *   currently ON  (1) -> sends 1 -> turns it OFF
     *   currently OFF (0) -> sends 2 -> turns it ON
     * This matches the lock convention (locked(1) -> send 1 = unlock). Sending the
     * "desired state" the naive way (on -> 2, off -> 1) makes every press a no-op:
     * the scooter beeps, accepts the frame, and nothing changes.
     */
    fun switchValue(currentlyOn: Boolean): Int =
        if (currentlyOn) SWITCH_OFF_VALUE else SWITCH_ON_VALUE

    /** Lock uses the same convention: currently locked -> 1 (unlock), unlocked -> 2 (lock). */
    fun lockValue(currentlyLocked: Boolean): Int =
        if (currentlyLocked) LOCK_UNLOCK_VALUE else LOCK_LOCK_VALUE

    /**
     * Gear as the stock app presents it. The scooter reports gearsVal 1..5 in status byte0 bits0-2,
     * but only 1, 2 and 3 can be written; 4 and 5 are treated as the top gear (the stock app
     * refuses to "switch to 3" when gearsVal is already 3, 4 or 5). Null for anything else.
     */
    fun uiGear(gearsVal: Int?): Int? = when (gearsVal) {
        1 -> 1
        2 -> 2
        3, 4, 5 -> 3
        else -> null
    }

    // ---- Torque / brake scaling (from DIYPerformanceTuning.vue sliderChange* handlers) ----

    /** Starting / max driving torque write: display 1..10 -> floor(v / 10 * 200). */
    fun torqueWriteByte(display1to10: Int): Int {
        val v = display1to10.coerceIn(1, 10)
        return kotlin.math.floor(v / 10.0 * 200).toInt()
    }

    /** Brake write: display 0..9 -> 1 when 0, else floor(v / 9 * 200). Never sends a literal 0. */
    fun brakeWriteByte(display0to9: Int): Int {
        val v = display0to9.coerceIn(0, 9)
        return if (v == 0) 1 else kotlin.math.floor(v / 9.0 * 200).toInt()
    }

    /**
     * Read-back of the tuning values (codes 61/62/63).
     *
     * Confirmed from the stock app's setConfigDIYResult listener: the reply normally carries one
     * data byte (the value). When the reply carries TWO data bytes (LEN == 2) the second byte is
     * the scooter's own scale, and the stock app divides by THAT instead of its built-in default
     * (99 for starting torque, 25 for max torque, 99 for brake). Ignoring it made the sliders show
     * the wrong position, e.g. a max-torque write of 120 read back as 48 and got pinned to 10.
     *
     * @param reportedMax data[1] when the frame had LEN == 2, else null.
     * Results are rounded (the stock app keeps floats; truncating turned brake level 1, which is
     * written as 22, into 0.99 -> 0).
     */
    fun startTorqueDisplay(raw: Int, reportedMax: Int? = null): Int =
        scaled(raw, reportedMax, 99.0, 10.0, 1, 10)

    fun maxTorqueDisplay(raw: Int, reportedMax: Int? = null): Int =
        scaled(raw, reportedMax, 25.0, 10.0, 1, 10)

    fun brakeDisplay(raw: Int, reportedMax: Int? = null): Int =
        scaled(raw, reportedMax, 99.0, 9.0, 0, 9)

    private fun scaled(raw: Int, reportedMax: Int?, defaultDivisor: Double, span: Double, lo: Int, hi: Int): Int {
        val divisor = reportedMax?.takeIf { it > 0 }?.toDouble() ?: defaultDivisor
        val v = raw / divisor * span
        return Math.round(v).toInt().coerceIn(lo, hi)
    }

    /** data byte the stock app's "restore defaults" sends to each of codes 60..63. */
    const val RESET_TO_DEFAULT_VALUE = 255

    /**
     * Builds a full outgoing command frame.
     * @param zt the sync byte -- pass the last ZT observed from an incoming frame
     *           (see IncomingFrame.zt). Use 0x5A as a fallback before any telemetry
     *           has been received (matches the app's own hardcoded initial state).
     */
    fun buildCommand(zt: Int, code: Int, data: List<Int>): ByteArray {
        val dataBytes = data.map { it and 0xFF }
        val len = dataBytes.size

        val body = ByteArray(3 + len) // zt, code, len, then data
        body[0] = zt.toByte()
        body[1] = code.toByte()
        body[2] = len.toByte()
        for (i in dataBytes.indices) body[3 + i] = dataBytes[i].toByte()

        var checksum = 0
        for (b in body) checksum += (b.toInt() and 0xFF)
        checksum = checksum and 0xFF

        return MAGIC + body + byteArrayOf(checksum.toByte())
    }

    data class IncomingFrame(
        val zt: Int,
        val code: Int,
        val len: Int,
        val data: ByteArray,
        val checksumOk: Boolean
    )

    /** Parses the first frame in a raw notify payload (no magic prefix expected). Null if too short. */
    fun parseIncoming(raw: ByteArray): IncomingFrame? = parseAt(raw, 0)?.first

    /**
     * Parses every complete frame in a notify payload. The stock app assumes one frame per
     * notification; being tolerant of several back-to-back frames costs nothing and avoids
     * silently dropping telemetry if the scooter ever batches them.
     */
    fun parseAll(raw: ByteArray): List<IncomingFrame> {
        val out = ArrayList<IncomingFrame>(2)
        var offset = 0
        while (offset < raw.size) {
            val (frame, next) = parseAt(raw, offset) ?: break
            out += frame
            offset = next
        }
        return out
    }

    private fun parseAt(raw: ByteArray, start: Int): Pair<IncomingFrame, Int>? {
        if (raw.size - start < 4) return null // need at least zt, code, len, checksum
        val zt = raw[start].toInt() and 0xFF
        val code = raw[start + 1].toInt() and 0xFF
        val len = raw[start + 2].toInt() and 0xFF
        val end = start + 3 + len + 1
        if (raw.size < end) return null
        val data = raw.copyOfRange(start + 3, start + 3 + len)
        val checksumByte = raw[start + 3 + len].toInt() and 0xFF

        var sum = zt + code + len
        for (b in data) sum += (b.toInt() and 0xFF)
        sum = sum and 0xFF

        return IncomingFrame(zt, code, len, data, sum == checksumByte) to end
    }

    /**
     * Ambient lamp color presets, extracted from the real app's own color list.
     * Only presets confirmed to fit in a single byte (hue <= 255) are included --
     * the original app's "red" (~355) and "purple" (~280) presets are excluded
     * because they'd overflow a single byte under the default encoding. See the
     * protocol spec's CODE 65 note before trusting hues above 255.
     */
    data class AmbientColor(val label: String, val hue: Int, val hex: String)
    val SAFE_AMBIENT_COLORS = listOf(
        AmbientColor("Orange", 32, "#FF8901"),
        AmbientColor("Yellow", 51, "#FAD817"),
        AmbientColor("Green", 156, "#00BA71"),
        AmbientColor("Blue", 187, "#00C2DE"),
        AmbientColor("White", 0, "#FFFFFF")
    )

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
}
