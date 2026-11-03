package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.Switch
import java.util.UUID

/**
 * Byte format of the Laifen Wave V1 (LFTB01). Pure translation, no I/O.
 *
 * Learned from public interoperability work (UrbanTechIO/Laifen,
 * marc-alexis-com/laifen-toolbox), verified on an LFTB01-P. No vendor code involved.
 *
 * Status:  AA 0A 02 [len] [payload] [xor]
 *          payload: active mode, 4 x (strength, range, speed), flight mode, battery %, ?,
 *                   brushing duration in seconds (16 bit big-endian), ...
 *          (Documented in laifen-toolbox/docs/PROTOCOL.md, verified here on 2026-10-29.)
 * Command: AA 04 01 04 [mode] [strength] [range] [speed] [xor]
 *          All three values at once; the brush rejects single-value writes.
 * Command: AA 05 01 02 [seconds hi] [seconds lo] [xor]   brushing duration
 * Command: AA [code] 01 01 [01/00] [xor]   switches: 07 flight mode, 0B reminder, 0E high frequency,
 *          0F motor
 * Version: AA 01 00 00 AB asks; the brush also sends it on its own right after connecting.
 *          Answer AA 01 02 [len] [payload] [xor], 9 or 19 bytes, shown like the official app does
 *          (e.g. "V2.0.8.Y44.303"). Read the same way by the official app.
 * Not sent on purpose: AA 09 00 00 A3 (history). The brush deletes every entry it hands out,
 * so asking would take it away from the official app. See README, "Not built yet".
 *
 * Payload layout and switch commands as documented (and confirmed on an LFTB01-P) in
 * laifen-toolbox/docs/PROTOCOL.md: 13 flight mode, 14 battery, 15 reminder, 16-17 brushing
 * time, 18 high frequency, 19 motor. A boolean is true only for exactly 01.
 * The flight-mode code 07 is not in laifen-toolbox: found by analysing the official app for
 * interoperability (only the protocol, no code taken over).
 * The checksum is the XOR of all preceding bytes.
 */
object LaifenWaveV1Codec {
    val SERVICE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    /**
     * Asks for the state; the brush answers with a status notification. A plain GATT read of
     * the characteristic does NOT return the state, only filler (01 02 03 ... on the LFTB01-P).
     */
    val READ_STATE: ByteArray = byteArrayOf(0xAA.toByte(), 0x0A, 0x00, 0x00, 0xA0.toByte())

    /** Asks for the firmware version; the answer is decoded by [decodeVersion]. */
    val READ_VERSION: ByteArray = byteArrayOf(0xAA.toByte(), 0x01, 0x00, 0x00, 0xAB.toByte())

    const val MODE_COUNT = 4
    private const val HEADER = 4
    private const val MIN_PAYLOAD = 20

    fun decodeStatus(data: ByteArray): BrushState? {
        if (data.size < HEADER + MIN_PAYLOAD + 1) return null
        val u = IntArray(data.size) { data[it].toInt() and 0xFF }
        if (u[0] != 0xAA || u[1] != 0x0A || u[2] != 0x02) return null

        val len = u[3]
        val csIndex = HEADER + len
        if (len < MIN_PAYLOAD || data.size <= csIndex) return null
        if (xor(data, csIndex) != u[csIndex]) return null

        val p = HEADER
        val active = u[p]
        if (active !in 0 until MODE_COUNT) return null

        val modes = (0 until MODE_COUNT).associateWith { m ->
            val b = p + 1 + 3 * m
            ModeSettings.of(STRENGTH to u[b], RANGE to u[b + 1], SPEED to u[b + 2])
        }
        return BrushState(
            batteryPercent = u[p + 14].coerceIn(0, 100),
            activeModeIndex = active,
            modes = modes,
            brushingDurationSeconds = (u[p + 16] shl 8) or u[p + 17],
            switches = mapOf(
                Switch.FLIGHT_MODE to (u[p + 13] == 1),
                Switch.REMINDER_30S to (u[p + 15] == 1),
                Switch.HIGH_FREQUENCY to (u[p + 18] == 1),
                Switch.MOTOR to (u[p + 19] == 1),
            ),
        )
    }

    /**
     * The firmware version, written like the official app: "V", then numbers, letters and dots
     * in a fixed pattern per length. Null for anything else.
     */
    fun decodeVersion(data: ByteArray): String? {
        if (data.size < HEADER + 1) return null
        val u = IntArray(data.size) { data[it].toInt() and 0xFF }
        if (u[0] != 0xAA || u[1] != 0x01) return null
        val len = u[3]
        val csIndex = HEADER + len
        if (data.size <= csIndex || xor(data, csIndex) != u[csIndex]) return null

        val v = u.copyOfRange(HEADER, csIndex)
        fun n(vararg i: Int) = i.joinToString("") { v[it].toString() }
        fun c(i: Int) = v[i].toChar()
        return when (len) {
            9 -> "V${n(0)}.${n(1)}.${n(2)}.${c(3)}${n(4, 5)}.${n(6, 7, 8)}"
            19 -> "V${n(0)}.${n(1)}.${n(2, 3)}.${c(4)}${c(5)}${c(6)}.${c(7)}${n(8, 9)}." +
                "${c(10)}${n(11, 12)}.${c(13)}${n(14, 15)}.${n(16, 17, 18)}"
            else -> null
        }
    }

    /** Expects settings already validated against the mode's spec. */
    fun encodeModeSettings(modeIndex: Int, settings: ModeSettings): ByteArray {
        val body = byteArrayOf(
            0xAA.toByte(), 0x04, 0x01, 0x04,
            modeIndex.toByte(),
            requireNotNull(settings[STRENGTH]).toByte(),
            requireNotNull(settings[RANGE]).toByte(),
            requireNotNull(settings[SPEED]).toByte(),
        )
        return body + xor(body, body.size).toByte()
    }

    fun encodeBrushingDuration(seconds: Int): ByteArray {
        val body = byteArrayOf(
            0xAA.toByte(), 0x05, 0x01, 0x02,
            (seconds shr 8).toByte(),
            (seconds and 0xFF).toByte(),
        )
        return body + xor(body, body.size).toByte()
    }

    /** Null for switches the V1 doesn't have (the Wave Pro features). */
    fun encodeSwitch(switch: Switch, on: Boolean): ByteArray? {
        val code = when (switch) {
            Switch.FLIGHT_MODE -> 0x07
            Switch.REMINDER_30S -> 0x0B
            Switch.HIGH_FREQUENCY -> 0x0E
            Switch.MOTOR -> 0x0F
            // Wave Pro features: not on the V1.
            else -> return null
        }
        val body = byteArrayOf(0xAA.toByte(), code.toByte(), 0x01, 0x01, if (on) 0x01 else 0x00)
        return body + xor(body, body.size).toByte()
    }

    private fun xor(bytes: ByteArray, length: Int): Int {
        var cs = 0
        for (i in 0 until length) cs = cs xor (bytes[i].toInt() and 0xFF)
        return cs
    }
}
