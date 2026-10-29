package io.github.damnms.buerstli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Packets captured from a real LFTB01-P with nRF Connect. */
class BrushProtocolTest {

    private fun hex(s: String) = s.split("-", " ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()

    private val mode2Active = hex("AA-0A-02-14-01-03-01-05-04-06-06-06-06-06-0B-06-06-00-5E-00-00-78-00-00-9F")
    private val mode3Active = hex("AA-0A-02-14-02-03-01-05-04-06-06-06-06-06-0B-06-06-00-5E-00-00-78-00-00-9C")
    private val afterWrite = hex("AA-0A-02-14-02-03-01-05-04-06-06-06-06-05-0B-06-06-00-5E-00-00-78-00-00-9F")

    @Test
    fun parsesRealStatusPacket() {
        val s = BrushProtocol.parseStatus(mode2Active)!!
        assertEquals(1, s.activeModeIndex)
        assertEquals(BrushProtocol.ModeSettings(3, 1, 5), s.modes[0])
        assertEquals(BrushProtocol.ModeSettings(4, 6, 6), s.modes[1])
        assertEquals(BrushProtocol.ModeSettings(6, 6, 6), s.modes[2])
        assertEquals(BrushProtocol.ModeSettings(11, 6, 6), s.modes[3])
        assertFalse(s.flightMode)
        assertEquals(94, s.batteryPercent)
    }

    @Test
    fun followsModeSwitch() {
        assertEquals(2, BrushProtocol.parseStatus(mode3Active)!!.activeModeIndex)
    }

    @Test
    fun seesWrittenSettings() {
        assertEquals(BrushProtocol.ModeSettings(6, 6, 5), BrushProtocol.parseStatus(afterWrite)!!.modes[2])
    }

    @Test
    fun rejectsBadChecksum() {
        val broken = mode2Active.copyOf().also { it[it.size - 1] = 0x00 }
        assertNull(BrushProtocol.parseStatus(broken))
    }

    @Test
    fun rejectsShortOrForeignPackets() {
        assertNull(BrushProtocol.parseStatus(hex("AA-04-01-01-00-AE")))
        assertNull(BrushProtocol.parseStatus(ByteArray(0)))
    }

    @Test
    fun buildsTheCommandVerifiedOnTheBrush() {
        val cmd = BrushProtocol.buildModeSettings(2, BrushProtocol.ModeSettings(6, 6, 5))
        assertArrayEquals(hex("AA-04-01-04-02-06-06-05-AC"), cmd)
    }

    @Test
    fun clampsHighFrequencyStrength() {
        val cmd = BrushProtocol.buildModeSettings(3, BrushProtocol.ModeSettings(5, 6, 6))
        assertEquals(11, cmd[5].toInt())
    }
}
