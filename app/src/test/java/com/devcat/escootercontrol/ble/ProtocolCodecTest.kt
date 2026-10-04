package com.devcat.escootercontrol.ble

import org.junit.Assert
import org.junit.Test

/**
 * Pure-JVM tests for the wire protocol. Run with `./gradlew test`.
 * Vectors come straight from the protocol spec / stock app source.
 */
class ProtocolCodecTest {

    private fun hex(b: ByteArray) = ProtocolCodec.bytesToHex(b)

    @Test
    fun handshakeFrameMatchesStockApp() {
        // 5A + 01 + 00 = 5B, same as the literal the stock app sends.
        Assert.assertEquals("FAAFA55A01005B", hex(ProtocolCodec.buildCommand(0x5A, 1, emptyList())))
        Assert.assertEquals(
            listOf("FAAFA55A01005B", "FAAFA5FA01005B"),
            ProtocolCodec.HANDSHAKE_FRAMES.map { hex(it) }
        )
    }

    @Test
    fun lockFramesAreBuiltWithCorrectChecksum() {
        Assert.assertEquals("FAAFA55A3301018F", hex(ProtocolCodec.buildCommand(0x5A, 51, listOf(1))))
        Assert.assertEquals("FAAFA55A33010290", hex(ProtocolCodec.buildCommand(0x5A, 51, listOf(2))))
    }

    /** The bug that made headlight/cruise/kick-start beep but never change. */
    @Test
    fun switchTogglesPassCurrentStateThrough() {
        // currently ON -> send 1 (turn off); currently OFF -> send 2 (turn on)
        Assert.assertEquals(1, ProtocolCodec.switchValue(currentlyOn = true))
        Assert.assertEquals(2, ProtocolCodec.switchValue(currentlyOn = false))
        // and lock keeps its own (identical-convention) mapping
        Assert.assertEquals(1, ProtocolCodec.lockValue(currentlyLocked = true))
        Assert.assertEquals(2, ProtocolCodec.lockValue(currentlyLocked = false))
    }

    @Test
    fun headlightOnFrameFromOffState() {
        val f = ProtocolCodec.buildCommand(0x5A, ProtocolCodec.CODE_HEADLIGHT, listOf(ProtocolCodec.switchValue(false)))
        // 5A+45+01+02 = 90+69+1+2 = 162 = 0xA2
        Assert.assertEquals("FAAFA55A450102A2", hex(f))
    }

    @Test
    fun torqueAndBrakeWriteScaling() {
        Assert.assertEquals(20, ProtocolCodec.torqueWriteByte(1))
        Assert.assertEquals(120, ProtocolCodec.torqueWriteByte(6))
        Assert.assertEquals(200, ProtocolCodec.torqueWriteByte(10))
        Assert.assertEquals(1, ProtocolCodec.brakeWriteByte(0))
        Assert.assertEquals(22, ProtocolCodec.brakeWriteByte(1))
        Assert.assertEquals(200, ProtocolCodec.brakeWriteByte(9))
        // out-of-range input is clamped, never wraps a byte
        Assert.assertEquals(200, ProtocolCodec.torqueWriteByte(99))
    }

    @Test
    fun parseAllSplitsBackToBackFrames() {
        fun frame(zt: Int, code: Int, data: List<Int>): ByteArray {
            val body = listOf(zt, code, data.size) + data
            return (body + (body.sum() and 0xFF)).map { it.toByte() }.toByteArray()
        }
        val a = frame(0x5A, 17, listOf(0x08, 0x02))
        val b = frame(0x5A, 60, listOf(25))
        val frames = ProtocolCodec.parseAll(a + b)
        Assert.assertEquals(2, frames.size)
        Assert.assertEquals(17, frames[0].code)
        Assert.assertEquals(60, frames[1].code)
        Assert.assertTrue(frames.all { it.checksumOk })
    }

    @Test
    fun parseAllIgnoresTruncatedTail() {
        val whole = byteArrayOf(0x5A, 60, 1, 25, (0x5A + 60 + 1 + 25).toByte())
        val frames = ProtocolCodec.parseAll(whole + byteArrayOf(0x5A, 17)) // dangling partial frame
        Assert.assertEquals(1, frames.size)
    }

    private fun statusFrame(byte3: Int, byte4: Int): ProtocolCodec.IncomingFrame {
        val data = listOf(byte3, byte4, 0x00, 0x64, 0x00, 0x0A, 0, 0)
        val body = listOf(0x5A, 17, data.size) + data
        val raw = (body + (body.sum() and 0xFF)).map { it.toByte() }.toByteArray()
        return ProtocolCodec.parseIncoming(raw)!!
    }

    @Test
    fun statusFrameDecodesToggleBits() {
        // byte3: bit3 headlight, bit5 startup, bit6 cruise -> 0b01101000 = 0x68
        // byte4: bit1 locked, bit6 bt-bound             -> 0b01000010 = 0x42
        val t = TelemetryDecoder.apply(ScooterTelemetry(), statusFrame(0x68, 0x42))
        Assert.assertEquals(true, t.headlightOn)
        Assert.assertEquals(true, t.startupModeOn)
        Assert.assertEquals(true, t.cruiseOn)
        Assert.assertEquals(true, t.locked)
        Assert.assertEquals(true, t.bluetoothBound)

        val off = TelemetryDecoder.apply(ScooterTelemetry(), statusFrame(0x00, 0x00))
        Assert.assertEquals(false, off.headlightOn)
        Assert.assertEquals(false, off.cruiseOn)
        Assert.assertEquals(false, off.locked)
    }

    @Test
    fun statusFrameUpdatesZt() {
        val t = TelemetryDecoder.apply(ScooterTelemetry(), statusFrame(0, 0))
        Assert.assertEquals(0x5A, t.lastZt)
    }

    @Test
    fun configReadbackKeepsRawByte() {
        val body = listOf(0x5A, 61, 1, 120)
        val raw = (body + (body.sum() and 0xFF)).map { it.toByte() }.toByteArray()
        val t = TelemetryDecoder.apply(ScooterTelemetry(), ProtocolCodec.parseIncoming(raw)!!)
        Assert.assertEquals(120, t.rawStartingTorque)
        // 120/99*10 = 12.1 -> clamped to 10, same as the stock app's display math
        Assert.assertEquals(10, t.startingTorque)
    }

    @Test
    fun corruptChecksumIsFlaggedNotCrashing() {
        val bad = byteArrayOf(0x5A, 60, 1, 25, 0x00)
        val f = ProtocolCodec.parseIncoming(bad)!!
        Assert.assertFalse(f.checksumOk)
    }

    private fun cfgFrame(code: Int, vararg data: Int): ProtocolCodec.IncomingFrame {
        val body = listOf(0x5A, code, data.size) + data.toList()
        val raw = (body + (body.sum() and 0xFF)).map { it.toByte() }.toByteArray()
        return ProtocolCodec.parseIncoming(raw)!!
    }

    /** Two-byte reply: data[1] is the scooter's own scale, as in the stock app. */
    @Test
    fun readbackUsesReportedRangeWhenPresent() {
        // write 6/10 -> 120; scooter reports range 200 -> must read back as 6, not pinned to 10
        val t = TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(62, 120, 200))
        Assert.assertEquals(6, t.maxDrivingTorque)
        Assert.assertEquals(120, t.rawMaxDrivingTorque)
        val s = TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(61, 120, 200))
        Assert.assertEquals(6, s.startingTorque)
    }

    @Test
    fun readbackFallsBackToDefaultDivisorForOneByteReply() {
        // LEN == 1 -> stock defaults: start 99, max 25, brake 99
        Assert.assertEquals(5, TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(61, 50)).startingTorque)
        Assert.assertEquals(4, TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(62, 10)).maxDrivingTorque)
    }

    @Test
    fun brakeRoundTripSurvivesRounding() {
        // every UI level 0..9 must read back as itself when the scooter reports range 200
        for (level in 0..9) {
            val written = ProtocolCodec.brakeWriteByte(level)
            val t = TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(63, written, 200))
            Assert.assertEquals(level, t.brakeStrength)
        }
    }

    @Test
    fun torqueRoundTripSurvivesRounding() {
        for (level in 1..10) {
            val written = ProtocolCodec.torqueWriteByte(level)
            val t = TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(61, written, 200))
            Assert.assertEquals(level, t.startingTorque)
        }
    }

    @Test
    fun zeroReportedRangeIsIgnoredNotDividedBy() {
        val t = TelemetryDecoder.apply(ScooterTelemetry(), cfgFrame(61, 50, 0))
        Assert.assertEquals(5, t.startingTorque) // fell back to divisor 99
    }

    @Test
    fun resetFramesUse255() {
        Assert.assertEquals("FAAFA55A3C01FF", hex(ProtocolCodec.buildCommand(0x5A, 60, listOf(255))).take(14))
    }

    private fun frameOf(code: Int, vararg data: Int): ProtocolCodec.IncomingFrame {
        val body = listOf(0x5A, code, data.size) + data.toList()
        val raw = (body + (body.sum() and 0xFF)).map { it.toByte() }.toByteArray()
        return ProtocolCodec.parseIncoming(raw)!!
    }

    /** Real status frames captured from the scooter (see debug log). */
    @Test
    fun statusDecodesGearAndUnitFromRealCapture() {
        // 03 01 00 00 02 F1 05 09 00 00 -> gear 3, km, no light
        val a = TelemetryDecoder.apply(ScooterTelemetry(), frameOf(17, 0x03, 0x01, 0x00, 0x00, 0x02, 0xF1, 0x05, 0x09, 0x00, 0x00))
        Assert.assertEquals(3, a.gear)
        Assert.assertEquals(false, a.speedUnitMiles)
        Assert.assertEquals(false, a.headlightOn)
        // 0B ... -> gear 3 + headlight bit
        val b = TelemetryDecoder.apply(a, frameOf(17, 0x0B, 0x01, 0x00, 0x00, 0x02, 0xF1, 0x05, 0x03, 0x00, 0x00))
        Assert.assertEquals(3, b.gear)
        Assert.assertEquals(true, b.headlightOn)
    }

    @Test
    fun statusBit7MeansMiles() {
        // 0x83 = unit bit + gear 3
        val t = TelemetryDecoder.apply(ScooterTelemetry(), frameOf(17, 0x83, 0x01, 0x00, 0x00, 0x02, 0xF1, 0x05, 0x09, 0x00, 0x00))
        Assert.assertEquals(true, t.speedUnitMiles)
        Assert.assertEquals(3, t.gear)
    }

    @Test
    fun gearMappingMatchesStockApp() {
        Assert.assertEquals(1, ProtocolCodec.uiGear(1))
        Assert.assertEquals(2, ProtocolCodec.uiGear(2))
        Assert.assertEquals(3, ProtocolCodec.uiGear(3))
        Assert.assertEquals(3, ProtocolCodec.uiGear(4))
        Assert.assertEquals(3, ProtocolCodec.uiGear(5))
        Assert.assertEquals(null, ProtocolCodec.uiGear(0))
        Assert.assertEquals(null, ProtocolCodec.uiGear(null))
    }

    @Test
    fun versionReplyDecodes() {
        val t = TelemetryDecoder.apply(ScooterTelemetry(), frameOf(171, 0x02, 0x1F))
        Assert.assertEquals(2, t.instrumentHardwareVersion)
        Assert.assertEquals(31, t.instrumentSoftwareVersion)
    }

    /** Real code-18 frame from the scooter: 9E CD 68 25 00 00 0A 2C 07 */
    @Test
    fun versionFrameDecodesFromRealCapture() {
        val t = TelemetryDecoder.apply(
            ScooterTelemetry(),
            frameOf(18, 0x9E, 0xCD, 0x68, 0x25, 0x00, 0x00, 0x0A, 0x2C, 0x07)
        )
        Assert.assertEquals(104, t.instrumentHardwareVersion)
        Assert.assertEquals(37, t.instrumentSoftwareVersion)
        Assert.assertEquals(10, t.controllerHardwareVersion)
        Assert.assertEquals(44, t.controllerSoftwareVersion)
        Assert.assertEquals(setOf(1, 2, 3), t.availableGears)
    }

    @Test
    fun versionQueryAndUnitFramesMatchStockCommands() {
        Assert.assertEquals("FAAFA55AAB01AD" + "%02X".format((0x5A + 0xAB + 1 + 0xAD) and 0xFF),
            hex(ProtocolCodec.buildCommand(0x5A, 171, listOf(173))))
        Assert.assertEquals("FAAFA55A4301" + "02" + "%02X".format((0x5A + 67 + 1 + 2) and 0xFF),
            hex(ProtocolCodec.buildCommand(0x5A, 67, listOf(2))))
    }

    @Test
    fun milesConversionUsesStockFactor() {
        Assert.assertEquals(31.06856, Units.speed(50.0, true), 0.0001)
        Assert.assertEquals(50.0, Units.speed(50.0, false), 0.0)
        Assert.assertEquals("mph", Units.speedLabel(true))
        Assert.assertEquals("km", Units.distanceLabel(false))
    }

    /** Real code-19 capture: 00 00 00 00 00 00 03 12 00 00 00 00 (throttle raw idling at 0x0312). */
    @Test
    fun sensorFrameDecodesFromRealCapture() {
        val t = TelemetryDecoder.apply(
            ScooterTelemetry(),
            frameOf(19, 0, 0, 0, 0, 0, 0, 0x03, 0x12, 0, 0, 0, 0)
        )
        Assert.assertEquals(0, t.throttle)
        Assert.assertEquals(0, t.brake1)
        Assert.assertEquals(0, t.brake2)
        Assert.assertEquals(786, t.throttleRaw)
        Assert.assertEquals(0, t.brake1Raw)
        val moved = TelemetryDecoder.apply(t, frameOf(19, 0x01, 0x2C, 0, 0, 0, 0, 0x03, 0x40, 0, 0, 0, 0))
        Assert.assertEquals(300, moved.throttle)
        Assert.assertEquals(832, moved.throttleRaw)
    }

    @Test
    fun batteryFrameDecodesWithOriginalOffsets() {
        // id=0x0005 (flags byte = data[1] = 0x05: charging + low-voltage), 53.40 V, 1.00 A,
        // 12 cycles, rated 3000, remaining 1400, 25 degrees
        val t = TelemetryDecoder.apply(
            ScooterTelemetry(),
            frameOf(31, 0x00, 0x05, 0x14, 0xDC, 0x00, 0x64, 0x00, 0x0C, 0x0B, 0xB8, 0x05, 0x78, 0x00, 0x19)
        )
        val b = t.bms!!
        Assert.assertEquals(5, b.id)
        Assert.assertEquals(53.40, b.voltage, 0.0001)
        Assert.assertEquals(1.00, b.current, 0.0001)
        Assert.assertEquals(12, b.cycles)
        Assert.assertEquals(3000, b.ratedCapacity)
        Assert.assertEquals(1400, b.remainingCapacity)
        Assert.assertEquals(25, b.temperature)
        Assert.assertTrue(b.charging)
        Assert.assertFalse(b.discharging)
        Assert.assertTrue(b.lowVoltageProtection)
        Assert.assertFalse(b.anomaly)
    }

    @Test
    fun shortBatteryFrameIsIgnored() {
        val t = TelemetryDecoder.apply(ScooterTelemetry(), frameOf(31, 0x00, 0x05))
        Assert.assertEquals(null, t.bms)
    }

    @Test
    fun workModeDecodesAndIgnoresQueryEcho() {
        Assert.assertEquals(3, TelemetryDecoder.apply(ScooterTelemetry(), frameOf(74, 3)).workMode)
        Assert.assertEquals(2, TelemetryDecoder.apply(ScooterTelemetry(), frameOf(74, 2, 0)).workMode)
        Assert.assertEquals(null, TelemetryDecoder.apply(ScooterTelemetry(), frameOf(74, 0)).workMode)
        Assert.assertEquals(null, TelemetryDecoder.apply(ScooterTelemetry(), frameOf(74, 9)).workMode)
    }
}
