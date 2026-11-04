package io.github.damnms.buerstli.drivers.xiaomi

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * AES-CCM decryption (RFC 3610), built on plain AES because neither the JVM nor Android ships CCM.
 * Only what MiBeacon needs: a 16-byte key, a 12-byte nonce, a short tag.
 */
internal object AesCcm {
    private const val BLOCK = 16

    /** The plaintext, or null if the tag doesn't match (wrong key or damaged frame). */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray? {
        require(key.size == 16 && nonce.size in 7..13 && tag.size in 4..16 && tag.size % 2 == 0)
        val l = 15 - nonce.size
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

        fun counterBlock(i: Int) = ByteArray(BLOCK).also { a ->
            a[0] = (l - 1).toByte()
            nonce.copyInto(a, 1)
            for (k in 0 until l) a[BLOCK - 1 - k] = (i ushr (8 * k)).toByte()
        }

        // Decrypt: counter mode, blocks 1, 2, … for the data.
        val plain = ByteArray(ciphertext.size)
        var offset = 0
        var counter = 1
        while (offset < ciphertext.size) {
            val stream = aes.doFinal(counterBlock(counter++))
            for (k in 0 until minOf(BLOCK, ciphertext.size - offset)) {
                plain[offset + k] = (ciphertext[offset + k].toInt() xor stream[k].toInt()).toByte()
            }
            offset += BLOCK
        }

        // Authenticate: CBC-MAC over B0, the associated data and the plaintext.
        val b0 = ByteArray(BLOCK)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((tag.size - 2) / 2) shl 3) or (l - 1)).toByte()
        nonce.copyInto(b0, 1)
        for (k in 0 until l) b0[BLOCK - 1 - k] = (plain.size ushr (8 * k)).toByte()
        val macInput = b0 + padded(if (aad.isEmpty()) ByteArray(0) else byteArrayOf((aad.size ushr 8).toByte(), aad.size.toByte()) + aad) + padded(plain)
        var x = ByteArray(BLOCK)
        for (block in macInput.indices step BLOCK) {
            x = aes.doFinal(ByteArray(BLOCK) { (x[it].toInt() xor macInput[block + it].toInt()).toByte() })
        }
        val s0 = aes.doFinal(counterBlock(0))
        var diff = 0
        for (k in tag.indices) diff = diff or ((x[k].toInt() xor s0[k].toInt()) xor tag[k].toInt())
        return if (diff and 0xFF == 0) plain else null
    }

    private fun padded(b: ByteArray) = if (b.size % BLOCK == 0) b else b + ByteArray(BLOCK - b.size % BLOCK)
}
