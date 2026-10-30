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

    /**
     * What gives the device away inside raw data: its address bytes (forwards and reversed) and
     * the address suffixes written as ASCII text, like the "1DEE" in a name "LFTB01-P-1DEE".
     */
    private val patterns: List<ByteArray> = bytes?.let { a ->
        val device = a.copyOfRange(3, 6)
        val asText = suffixes(a).flatMap { listOf(it.uppercase(), it.lowercase()) }.map { it.toByteArray(Charsets.US_ASCII) }
        listOf(device, device.reversedArray()) + asText
    } ?: emptyList()

    fun hex(data: ByteArray): String {
        val masked = BooleanArray(data.size)
        for (pattern in patterns) {
            for (i in 0..data.size - pattern.size) {
                if (pattern.indices.all { data[i + it] == pattern[it] }) {
                    for (j in pattern.indices) masked[i + j] = true
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
        for (suffix in suffixes(a)) out = out.replace(suffix, "X".repeat(suffix.length), ignoreCase = true)
        return out
    }

    /** The last three and the last two address bytes as hex text, longest first: "011DEE", "1DEE". */
    private fun suffixes(a: ByteArray): List<String> = listOf(3, 2).map { count ->
        a.takeLast(count).joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }
    }
}
