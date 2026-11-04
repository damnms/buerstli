package io.github.damnms.buerstli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrushKeysTest {
    private val key = ByteArray(16) { (0x10 + it).toByte() }

    @Test
    fun `a key may be typed or pasted with spaces, colons or dashes, in any case`() {
        assertArrayEquals(key, BrushKeys.parse("101112131415161718191a1b1c1d1e1f", 16))
        assertArrayEquals(key, BrushKeys.parse(" 10:11:12:13 14-15-16-17 18191A1B1C1D1E1F\n", 16))
    }

    @Test
    fun `too short, too long or not hex is refused`() {
        assertNull(BrushKeys.parse("101112", 16))
        assertNull(BrushKeys.parse("101112131415161718191a1b1c1d1e1f00", 16))
        assertNull(BrushKeys.parse("101112131415161718191a1b1c1d1e1g", 16))
        assertNull(BrushKeys.parse("", 16))
    }
}
