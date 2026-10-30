package io.github.damnms.buerstli

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressMaskTest {
    private val mask = AddressMask("C0:00:00:01:1D:EE")

    @Test
    fun `masks the device part of the address`() {
        assertEquals("C0:00:00:XX:XX:XX", mask.address())
    }

    @Test
    fun `masks address suffixes in names, keeps the model`() {
        assertEquals("LFTB01-P-XXXX", mask.text("LFTB01-P-1DEE"))
        assertEquals("found LFTB01-P-XXXX (-61 dBm)", mask.text("found LFTB01-P-1dee (-61 dBm)"))
    }

    @Test
    fun `masks address bytes in data, forwards and reversed`() {
        assertEquals("XX-XX-XX-00-00-C0", mask.hex(byteArrayOf(0xEE.toByte(), 0x1D, 0x01, 0x00, 0x00, 0xC0.toByte())))
        assertEquals("09-C0-00-00-XX-XX-XX", mask.hex(byteArrayOf(0x09, 0xC0.toByte(), 0x00, 0x00, 0x01, 0x1D, 0xEE.toByte())))
        assertEquals("AA-0A-02", mask.hex(byteArrayOf(0xAA.toByte(), 0x0A, 0x02)))
    }

    @Test
    fun `without a valid address nothing is masked`() {
        val none = AddressMask("??")
        assertEquals("XX", none.address())
        assertEquals("LFTB01-P-1DEE", none.text("LFTB01-P-1DEE"))
    }

    // Found in a real report: the device name characteristic holds "LFTB01-P-1DEE" as ASCII, so its
    // hex showed 31-44-45-45 ("1DEE") although the text next to it was masked.
    @Test
    fun `address suffixes written as text are masked in the hex, too`() {
        assertEquals("4C-46-54-42-30-31-2D-50-2D-XX-XX-XX-XX", mask.hex("LFTB01-P-1DEE".toByteArray()))
        assertEquals("XX-XX-XX-XX-XX-XX", mask.hex("011dee".toByteArray()))
    }
}
