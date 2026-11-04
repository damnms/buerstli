package io.github.damnms.buerstli

import android.content.Context

/**
 * Keys the user entered for brushes that broadcast encrypted, by Bluetooth address. Kept in the
 * app's private storage (no backup, see the manifest) and never sent anywhere.
 */
class BrushKeys(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("brush_keys", Context.MODE_PRIVATE)

    fun load(address: String): ByteArray? = prefs.getString(address.uppercase(), null)?.let { parse(it, it.length / 2) }

    fun save(address: String, key: ByteArray) {
        prefs.edit().putString(address.uppercase(), key.joinToString("") { "%02x".format(it.toInt() and 0xFF) }).apply()
    }

    fun remove(address: String) {
        prefs.edit().remove(address.uppercase()).apply()
    }

    companion object {
        /** A key typed or pasted by the user: hex, spaces, colons and dashes allowed. Null if it isn't [bytes] long. */
        fun parse(text: String, bytes: Int): ByteArray? {
            val hex = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
            if (hex.length != bytes * 2 || hex.any { it.digitToIntOrNull(16) == null }) return null
            return ByteArray(bytes) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
