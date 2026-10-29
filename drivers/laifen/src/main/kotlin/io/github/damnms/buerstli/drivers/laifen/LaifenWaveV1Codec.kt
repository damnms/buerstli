package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
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
 *          The duration field was found on 2026-10-29: earlier work assumed V1 doesn't report it.
 * Command: AA 04 01 04 [mode] [strength] [range] [speed] [xor]
 *          All three values at once; the brush rejects single-value writes.
 * Command: AA 05 01 02 [seconds hi] [seconds lo] [xor]   brushing duration
 * The checksum is the XOR of all preceding bytes.
 */
object LaifenWaveV1Codec {
    val SERVICE: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    const val MODE_COUNT = 4
    private const val HEADER = 4
    private const val MIN_PAYLOAD = 18

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
            flightMode = u[p + 13] != 0,
            brushingDurationSeconds = (u[p + 16] shl 8) or u[p + 17],
        )
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

    private fun xor(bytes: ByteArray, length: Int): Int {
        var cs = 0
        for (i in 0 until length) cs = cs xor (bytes[i].toInt() and 0xFF)
        return cs
    }
}
