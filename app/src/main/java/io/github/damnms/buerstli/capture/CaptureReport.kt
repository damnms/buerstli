package io.github.damnms.buerstli.capture

import java.util.Locale
import java.util.UUID

/** Everything a capture of an unsupported brush recorded. Plain data, rendered as text. */
data class CaptureReport(
    val appVersion: String,
    val phone: String,
    val brand: String,
    val deviceName: String?,
    val address: String,
    val advertisements: List<AdvertisementSample>,
    val services: List<ServiceDump>,
    val notifications: List<NotificationSample>,
    val problems: List<String> = emptyList(),
) {
    /** The address as bytes; its last three bytes identify the device and are masked everywhere. */
    private val addressBytes: ByteArray? = address.split(":")
        .mapNotNull { it.toIntOrNull(16)?.toByte() }
        .takeIf { it.size == 6 }
        ?.toByteArray()

    /** Hex, with the device-specific address bytes masked, forwards or reversed (brushes do both). */
    private fun hexMasked(bytes: ByteArray): String {
        val masked = BooleanArray(bytes.size)
        addressBytes?.let { a ->
            val device = a.copyOfRange(3, 6)
            for (pattern in listOf(device, device.reversedArray())) {
                for (i in 0..bytes.size - 3) {
                    if (bytes[i] == pattern[0] && bytes[i + 1] == pattern[1] && bytes[i + 2] == pattern[2]) {
                        masked[i] = true; masked[i + 1] = true; masked[i + 2] = true
                    }
                }
            }
        }
        return bytes.indices.joinToString("-") { i ->
            if (masked[i]) "XX" else String.format(Locale.ROOT, "%02X", bytes[i].toInt() and 0xFF)
        }
    }

    /** Names like "LFTB01-P-1DEE" end in address bytes; mask those, keep the model part. */
    private fun textMasked(text: String): String {
        val a = addressBytes ?: return text
        var out = text
        for (count in listOf(3, 2)) {
            val suffix = a.takeLast(count).joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
            out = out.replace(suffix, "X".repeat(suffix.length), ignoreCase = true)
        }
        return out
    }

    fun render(): String = buildString {
        appendLine("# Bürstli capture report")
        appendLine()
        appendLine("- Brush: $brand, Bluetooth name \"${deviceName?.let { textMasked(it) } ?: "?"}\", address ${maskAddress(address)}")
        appendLine("- App: Bürstli $appVersion")
        appendLine("- Phone: $phone")
        problems.forEach { appendLine("- Problem: $it") }

        appendLine()
        appendLine("## Advertisements (${advertisements.size} distinct)")
        advertisements.forEach { a ->
            val parts = mutableListOf("${seconds(a.timeMs)} rssi ${a.rssi}")
            if (a.serviceUuids.isNotEmpty()) parts += "services " + a.serviceUuids.joinToString { short(it) }
            a.manufacturerData.forEach { (id, data) -> parts += "manufacturer ${hex16(id)}: ${hexMasked(data)}" }
            appendLine("- " + parts.joinToString(" · "))
        }

        appendLine()
        appendLine("## GATT")
        services.forEach { s ->
            appendLine("### Service ${short(s.uuid)}")
            s.characteristics.forEach { c ->
                val props = c.properties.sortedBy { PROPERTY_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
                    .joinToString(", ")
                val value = when {
                    c.uuid in PRIVATE -> " = [removed for privacy]"
                    c.value != null -> " = ${hexMasked(c.value)}" + (text(c.value)?.let { " \"${textMasked(it)}\"" } ?: "")
                    else -> ""
                }
                val error = c.error?.let { " (error: $it)" } ?: ""
                appendLine("- ${short(c.uuid)} [$props]$value$error")
            }
        }

        appendLine()
        appendLine("## Notifications (${notifications.size})")
        notifications.forEach { n ->
            val value = if (n.characteristic in PRIVATE) "[removed for privacy]" else hexMasked(n.value)
            appendLine("- +${seconds(n.timeMs)} ${short(n.characteristic)} $value")
        }
    }

    private companion object {
        private fun sig(short: Int) = UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))

        val PROPERTY_ORDER = listOf("READ", "WRITE", "WRITE_NO_RESPONSE", "NOTIFY", "INDICATE")

        /** Serial number and system id identify one physical device (system id often contains the address). */
        val PRIVATE = setOf(sig(0x2A25), sig(0x2A23))

        fun short(uuid: UUID): String {
            val s = uuid.toString()
            return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
                "0x" + s.substring(4, 8).uppercase(Locale.ROOT)
            } else {
                s
            }
        }

        fun maskAddress(address: String): String {
            val parts = address.split(":")
            return if (parts.size == 6) (parts.take(3) + listOf("XX", "XX", "XX")).joinToString(":") else "XX"
        }

        fun hex16(v: Int) = String.format(Locale.ROOT, "0x%04X", v)
        fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2fs", ms / 1000.0)

        /** The value as text if it looks like text (model names, firmware versions). */
        fun text(bytes: ByteArray): String? {
            if (bytes.size < 2) return null
            val ok = bytes.all { it in 0x20..0x7E }
            return if (ok) String(bytes, Charsets.US_ASCII) else null
        }
    }
}

data class AdvertisementSample(
    val timeMs: Long,
    val rssi: Int,
    val serviceUuids: List<UUID>,
    val manufacturerData: Map<Int, ByteArray>,
)

data class ServiceDump(val uuid: UUID, val characteristics: List<CharacteristicDump>)

data class CharacteristicDump(
    val uuid: UUID,
    val properties: Set<String>,
    val value: ByteArray?,
    val error: String?,
)

data class NotificationSample(val timeMs: Long, val characteristic: UUID, val value: ByteArray)
