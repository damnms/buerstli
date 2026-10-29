package io.github.damnms.buerstli

import java.util.Locale

/**
 * Hides the device-specific part (last three bytes) of a Bluetooth address wherever it shows up:
 * the address itself, data that contains it (some brushes send it, byte-reversed, in their radio
 * packets) and names ending in it (e.g. "LFTB01-P-1DEE"). Used for reports and the nerd log.
 */
class AddressMask(private val address: String) {

    private val bytes: ByteArray? = address.split(":")
        .mapNotNull { it.toIntOrNull(16)?.toByte() }
        .takeIf { it.size == 6 }
        ?.toByteArray()

    fun address(): String {
        val parts = address.split(":")
        return if (bytes != null) (parts.take(3) + listOf("XX", "XX", "XX")).joinToString(":") else "XX"
    }

    fun hex(data: ByteArray): String {
        val masked = BooleanArray(data.size)
        bytes?.let { a ->
            val device = a.copyOfRange(3, 6)
            for (pattern in listOf(device, device.reversedArray())) {
                for (i in 0..data.size - 3) {
                    if (data[i] == pattern[0] && data[i + 1] == pattern[1] && data[i + 2] == pattern[2]) {
                        masked[i] = true; masked[i + 1] = true; masked[i + 2] = true
                    }
                }
            }
        }
        return data.indices.joinToString("-") { i ->
            if (masked[i]) "XX" else String.format(Locale.ROOT, "%02X", data[i].toInt() and 0xFF)
        }
    }

    fun text(text: String): String {
        val a = bytes ?: return text
        var out = text
        for (count in listOf(3, 2)) {
            val suffix = a.takeLast(count).joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
            out = out.replace(suffix, "X".repeat(suffix.length), ignoreCase = true)
        }
        return out
    }
}
