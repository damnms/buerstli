package io.github.damnms.buerstli.drivers.xiaomi

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.KeyStatus
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

    // --- encrypted brushes: the real T700 frame from xiaomi-ble, with the key from its test ---

    private val t700Address = "ED:DE:34:3F:48:0C"
    private val t700Key = hex("13-30-B9-9C-DE-D1-32-58-AC-C3-91-62-7E-97-71-F7")
    private fun t700Ad(frame: String = t700Encrypted) =
        Advertisement(null, serviceData = mapOf(MiBeaconCodec.SERVICE to hex(frame)), address = t700Address)

    @Test
    fun `the real T700 frame decrypts to what xiaomi-ble reads`() {
        val data = hex(t700Encrypted)
        val objects = MiBeaconCodec.decrypt(data, MiBeaconCodec.parse(data)!!, t700Key, t700Address)!!
        assertEquals(MiBeaconCodec.BRUSHING, objects.single().id)
        assertArrayEquals(hex("01-03-62-9D-64-53"), objects.single().data) // end of brushing, score 83
    }

    @Test
    fun `a wrong key or a damaged frame doesn't decrypt`() {
        val data = hex(t700Encrypted)
        val beacon = MiBeaconCodec.parse(data)!!
        assertNull(MiBeaconCodec.decrypt(data, beacon, ByteArray(16), t700Address))
        assertNull(MiBeaconCodec.decrypt(data, beacon, t700Key, "ED:DE:34:3F:48:0D"))
        val damaged = data.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertNull(MiBeaconCodec.decrypt(damaged, MiBeaconCodec.parse(damaged)!!, t700Key, t700Address))
    }

    @Test
    fun `a T700 asks for the key right away`() {
        assertEquals(KeyStatus.MISSING, XiaomiT700Driver.open(NoLink).state.value.keyStatus)
        assertEquals(16, XiaomiT700Driver.key.bytes)
    }

    @Test
    fun `with the key, the T700 is read`() {
        val s = XiaomiT700Driver.open(NoLink)
        s.setKey(t700Key)
        s.onAdvertisement(t700Ad())
        val state = s.state.value
        assertEquals(KeyStatus.OK, state.keyStatus)
        assertEquals(Activity.OFF, state.activity)
        assertEquals(83, state.brushingScore)
    }

    @Test
    fun `a key entered later is tried on the last frame at once`() {
        val s = XiaomiT700Driver.open(NoLink)
        s.onAdvertisement(t700Ad())
        assertEquals(KeyStatus.MISSING, s.state.value.keyStatus)
        s.setKey(t700Key)
        assertEquals(KeyStatus.OK, s.state.value.keyStatus)
        assertEquals(83, s.state.value.brushingScore)
    }

    @Test
    fun `a wrong key is reported, and a right one fixes it`() {
        val s = XiaomiT700Driver.open(NoLink)
        s.setKey(ByteArray(16))
        s.onAdvertisement(t700Ad())
        assertEquals(KeyStatus.WRONG, s.state.value.keyStatus)
        assertNull(s.state.value.brushingScore)
        s.setKey(t700Key)
        assertEquals(KeyStatus.OK, s.state.value.keyStatus)
    }

    @Test
    fun `one damaged frame after a working key doesn't make it wrong, two do`() {
        val s = XiaomiT700Driver.open(NoLink)
        s.setKey(t700Key)
        s.onAdvertisement(t700Ad())
        val damaged = t700Encrypted.replaceRange(15, 17, "49") // first ciphertext byte
        s.onAdvertisement(t700Ad(damaged))
        assertEquals(KeyStatus.OK, s.state.value.keyStatus)
        s.onAdvertisement(t700Ad(damaged))
        assertEquals(KeyStatus.WRONG, s.state.value.keyStatus)
    }

    @Test
    fun `the T500 doesn't ask for a key, it broadcasts in clear`() {
        val s = session()
        s.onAdvertisement(ad(t500Brushing))
        assertNull(s.state.value.keyStatus)
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
