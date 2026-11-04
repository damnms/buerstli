package io.github.damnms.buerstli.drivers.xiaomi

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.NoLink
import io.github.damnms.buerstli.domain.SupportLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real frames from the test suites of xiaomi-ble (Apache-2.0) and ble_monitor (MIT); expected
 * values are what those parsers decode for the same bytes. Frames marked "built" are put together
 * here from the documented object ids, for cases without a real frame.
 */
class XiaomiTest {
    private fun hex(s: String) = s.split("-").map { it.toInt(16).toByte() }.toByteArray()
    private fun ad(s: String) = Advertisement(null, serviceData = mapOf(MiBeaconCodec.SERVICE to hex(s)))

    /** Real T500 frame (same bytes in both test suites): brushing started, counter 3. */
    private val t500Brushing = "71-30-89-04-37-11-5B-17-43-71-E6-09-10-00-02-00-03"

    /** Real T700 frame from xiaomi-ble, encrypted (MiBeacon V5). */
    private val t700Encrypted = "48-58-06-08-C9-48-0E-F1-12-81-07-97-33-FC-14-00-00-56-44-DB-41"

    /** Built: version 5, objects only, T500 product id, frame counter 1, then [objects]. */
    private fun built(objects: String) = "40-50-89-04-01-$objects"

    private fun session() = XiaomiT500Driver.open(NoLink)

    private fun stateAfter(vararg frames: String): BrushState {
        val s = session()
        frames.forEach { s.onAdvertisement(ad(it)) }
        return s.state.value
    }

    @Test
    fun `the real T500 frame is read like the reference parsers read it`() {
        val beacon = MiBeaconCodec.parse(hex(t500Brushing))!!
        assertEquals(0x0489, beacon.productId)
        assertEquals(0x37, beacon.frameCounter)
        assertFalse(beacon.encrypted)
        assertEquals(1, beacon.objects.size)
        assertEquals(MiBeaconCodec.TOOTHBRUSH, beacon.objects[0].id)
        assertArrayEquals(hex("00-03"), beacon.objects[0].data)
    }

    @Test
    fun `brushing started`() {
        val s = stateAfter(t500Brushing)
        assertEquals(Activity.BRUSHING, s.activity)
        assertNull(s.brushingScore)
    }

    @Test
    fun `brushing ended with a score`() {
        // Built: object 0x0010 with 01-63, which both parsers decode as "off, score 99".
        val s = stateAfter(t500Brushing, built("10-00-02-01-63"))
        assertEquals(Activity.OFF, s.activity)
        assertEquals(99, s.brushingScore)
    }

    @Test
    fun `the score stays when the next session starts`() {
        val s = stateAfter(built("10-00-02-01-63"), t500Brushing)
        assertEquals(Activity.BRUSHING, s.activity)
        assertEquals(99, s.brushingScore)
    }

    @Test
    fun `battery and brush head in one frame`() {
        // Built: battery 80 %, consumable 70 % left, i.e. 30 % worn.
        val s = stateAfter(built("0A-10-01-50-13-10-01-46"))
        assertEquals(80, s.batteryPercent)
        assertEquals(30, s.brushHeadWearPercent)
    }

    @Test
    fun `the brushing object of newer brushes is read too`() {
        // Object 0x3003 as decoded in ble_monitor's tests: end, timestamp, score 83.
        val s = stateAfter(built("03-30-06-01-03-62-9D-64-53"))
        assertEquals(Activity.OFF, s.activity)
        assertEquals(83, s.brushingScore)
    }

    @Test
    fun `values out of range and unknown events change nothing`() {
        val s = stateAfter(built("0A-10-01-FF-10-00-02-02-63"))
        assertEquals(BrushState.UNKNOWN, s)
    }

    @Test
    fun `encrypted frames are recognized as such and skipped`() {
        val beacon = MiBeaconCodec.parse(hex(t700Encrypted))!!
        assertEquals(0x0806, beacon.productId)
        assertTrue(beacon.encrypted)
        assertTrue(beacon.objects.isEmpty())
        // Built: a T500 frame with the encryption bit set.
        assertEquals(BrushState.UNKNOWN, stateAfter("48-50-89-04-01-10-00-02-00-03"))
    }

    @Test
    fun `frames of another product are ignored`() {
        assertEquals(BrushState.UNKNOWN, stateAfter(t700Encrypted))
    }

    @Test
    fun `mesh frames and old versions are not read`() {
        assertNull(MiBeaconCodec.parse(hex("C0-50-89-04-01-10-00-02-00-03"))) // mesh
        assertNull(MiBeaconCodec.parse(hex("40-10-89-04-01-10-00-02-00-03"))) // version 1
        assertNull(MiBeaconCodec.parse(hex("40-50-89-04"))) // too short
    }

    @Test
    fun `an object that doesn't fit ends the list`() {
        val beacon = MiBeaconCodec.parse(hex(built("0A-10-01-50-10-00-05-01-63")))!!
        assertEquals(listOf(MiBeaconCodec.BATTERY), beacon.objects.map { it.id })
    }

    @Test
    fun `the capability byte and its extra I-O byte are skipped`() {
        // Built: capability included (0x20 in the capability byte: one I/O byte follows).
        val beacon = MiBeaconCodec.parse(hex("60-50-89-04-01-20-00-10-00-02-01-63"))!!
        assertEquals(MiBeaconCodec.TOOTHBRUSH, beacon.objects.single().id)
    }

    @Test
    fun `the driver is passive, experimental and only for the T500`() = runTest {
        assertTrue(XiaomiT500Driver.passive)
        assertEquals(SupportLevel.EXPERIMENTAL, XiaomiT500Driver.supportLevel)
        assertTrue(XiaomiT500Driver.recognizes(ad(t500Brushing)))
        assertFalse(XiaomiT500Driver.recognizes(ad(t700Encrypted)))
        assertFalse(XiaomiT500Driver.recognizes(Advertisement("Mi Toothbrush")))
        assertEquals(CommandResult.Unsupported, session().execute(BrushCommand.ActivateMode(0)))
    }
}
