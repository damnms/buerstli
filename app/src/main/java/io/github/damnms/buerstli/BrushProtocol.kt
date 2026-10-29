package io.github.damnms.buerstli

import java.util.UUID

/**
 * Wire protocol of the Laifen Wave (V1, model LFTB01).
 *
 * Learned from public interoperability work (UrbanTechIO/Laifen,
 * marc-alexis-com/laifen-toolbox) and verified on an LFTB01-P.
 * No vendor code involved.
 *
 * Status packet (notify/read on 0xFF02):
 *   AA 0A 02 [len] [payload: len bytes] [xor checksum over everything before]
 *   payload[0]       active mode index (0..3)
 *   payload[1..12]   4 x (strength, range, speed), mode 1..4
 *   payload[13]      flight mode (0/1)
 *   payload[14]      battery %
 */
object BrushProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val NAME_PREFIX = "LFTB"
    const val MODE_COUNT = 4
    const val HF_MODE_INDEX = 3

    private const val HEADER_SIZE = 4
    private const val MIN_PAYLOAD = 15

    data class ModeSettings(val strength: Int, val range: Int, val speed: Int)

    data class Status(
        val activeModeIndex: Int,
        val modes: List<ModeSettings>,
        val flightMode: Boolean,
        val batteryPercent: Int,
    )

    fun xor(bytes: ByteArray, length: Int = bytes.size): Int {
        var cs = 0
        for (i in 0 until length) cs = cs xor (bytes[i].toInt() and 0xFF)
        return cs
    }

    /** Returns null for anything that isn't a complete, checksum-valid status packet. */
    fun parseStatus(data: ByteArray): Status? {
        if (data.size < HEADER_SIZE + MIN_PAYLOAD + 1) return null
        val u = IntArray(data.size) { data[it].toInt() and 0xFF }
        if (u[0] != 0xAA || u[1] != 0x0A || u[2] != 0x02) return null

        val len = u[3]
        if (len < MIN_PAYLOAD || data.size < HEADER_SIZE + len + 1) return null
        val csIndex = HEADER_SIZE + len
        if (xor(data, csIndex) != u[csIndex]) return null

        val p = HEADER_SIZE
        val active = u[p]
        if (active !in 0 until MODE_COUNT) return null

        val modes = (0 until MODE_COUNT).map { m ->
            val base = p + 1 + 3 * m
            ModeSettings(strength = u[base], range = u[base + 1], speed = u[base + 2])
        }
        return Status(
            activeModeIndex = active,
            modes = modes,
            flightMode = u[p + 13] != 0,
            batteryPercent = u[p + 14].coerceIn(0, 100),
        )
    }

    /**
     * Write all three settings of one mode at once (the brush rejects single values):
     *   AA 04 01 04 [mode] [strength] [range] [speed] [xor]
     * Mode 4 (high frequency) uses strength 11..20, the others 1..10.
     */
    fun buildModeSettings(modeIndex: Int, settings: ModeSettings): ByteArray {
        val m = modeIndex.coerceIn(0, MODE_COUNT - 1)
        val s = if (m == HF_MODE_INDEX) settings.strength.coerceIn(11, 20) else settings.strength.coerceIn(1, 10)
        val r = settings.range.coerceIn(1, 10)
        val sp = settings.speed.coerceIn(1, 10)
        val body = byteArrayOf(0xAA.toByte(), 0x04, 0x01, 0x04, m.toByte(), s.toByte(), r.toByte(), sp.toByte())
        return body + xor(body).toByte()
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
}
