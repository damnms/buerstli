package io.github.damnms.buerstli.drivers.xiaomi

import java.util.Locale

/**
 * A MiBeacon's frame control in words, for reports and the diagnostics log: is the brush bound,
 * does it encrypt, which auth mode. Bits as documented by xiaomi-ble, see docs/XIAOMI.md.
 * Null if the data is too short to be a MiBeacon.
 */
fun describeMiBeacon(data: ByteArray): String? {
    if (data.size < 5) return null
    val control = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
    fun bit(n: Int) = (control ushr n) and 1 == 1
    val parts = mutableListOf("MiBeacon V${control ushr 12}")
    parts += String.format(Locale.ROOT, "product 0x%04X", MiBeaconCodec.productId(data))
    parts += if (bit(3)) "encrypted" else "not encrypted"
    parts += if (bit(8)) "bound" else "not bound"
    if (bit(9)) parts += "asks to be bound"
    parts += when ((control ushr 10) and 3) {
        0 -> "old auth"
        1 -> "secure auth"
        2 -> "standard auth"
        else -> "auth mode 3"
    }
    if (bit(7)) parts += "mesh"
    if (!bit(6)) parts += "no data"
    // Objects of a frame in clear; encrypted ones only Bürstli with the key can read.
    if (!bit(3)) {
        MiBeaconCodec.parse(data)?.objects?.forEach { parts += describe(it) }
    }
    return parts.joinToString(", ")
}

internal fun describe(obj: MiObject): String {
    val known = obj.id in setOf(MiBeaconCodec.TOOTHBRUSH, MiBeaconCodec.BATTERY, MiBeaconCodec.CONSUMABLE, MiBeaconCodec.BRUSHING)
    val hex = obj.data.joinToString("-") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
    return String.format(Locale.ROOT, "object 0x%04X = %s", obj.id, hex.ifEmpty { "(empty)" }) + if (known) "" else " (unknown)"
}
