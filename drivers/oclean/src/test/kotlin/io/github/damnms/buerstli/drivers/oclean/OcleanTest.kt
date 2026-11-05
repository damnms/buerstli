package io.github.damnms.buerstli.drivers.oclean

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.BATTERY
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.FIRMWARE
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.HARDWARE
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.MODEL_NUMBER
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.REPLY
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.SESSIONS
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.SESSION_REQUEST
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Frames marked "real" are notifications of real brushes from the tests of ha-oclean-integration
 * (MIT), an Oclean X (OCLEANY3M) in February 2026, and one record an Oclean X owner posted in its
 * issue #137. Expected values are what the formats in
 * docs/OCLEAN.md give for those bytes. Frames marked "built" are put together here from the
 * record layout, for cases without a real frame.
 */
class OcleanTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private val utc = ZoneOffset.UTC

    /** Real: the newest session inline (count 0), 2026-02-21 16:44:28, set 120 s, brushed 120 s. */
    private val inline2min = "03072a422300001a0215102c1c00007800780201"

    /** Real: count 1, but only the short record follows; 2026-02-24 21:53:19, 150 s. */
    private val countOneShort = "03072a422300011a02181535134c009600960b03"

    /** Real: reply to 0303 of an idle Oclean X; byte 3 of the payload is the battery, 29 %. */
    private val status = "0303020e4b1d0000"

    /** Real (issue #137): a full record of a run of about 8 s, 2026-09-17 20:13:50, set 120 s, score 1. */
    private val record8s = "1a0911140d3200007800086400000000001100000000000007" +
        "0a1f0012100f00000100ffffffffffffff"

    /**
     * Built: a full 42-byte record. [valid] is the time brushed; an aborted run keeps the set time
     * in bytes 7-8, as ha-oclean-integration saw on a whole buffer of a real OCLEANY3M (issue #137).
     */
    private fun record(day: Int, minute: Int, target: Int, valid: Int, score: Int): ByteArray {
        val r = ByteArray(OcleanCodec.RECORD_SIZE)
        byteArrayOf(26, 3, day.toByte(), 7, minute.toByte(), 0).copyInto(r)
        r[7] = (target shr 8).toByte(); r[8] = target.toByte()
        r[9] = (valid shr 8).toByte(); r[10] = valid.toByte()
        r[33] = score.toByte()
        return r
    }

    /** Header, then [records] split into notifications of 20 bytes, as with the default MTU. */
    private fun packets(vararg records: ByteArray): List<ByteArray> {
        val all = hex("03072a4223") + byteArrayOf(0, records.size.toByte()) + records.reduce { a, b -> a + b }
        return all.toList().chunked(20).map { it.toByteArray() }
    }

    /** A pretend Oclean: answers reads from [values]; what it sends when written to is up to [onWrite]. */
    private class FakeOclean(val values: Map<UUID, ByteArray>) : BrushLink {
        val writes = mutableListOf<Pair<UUID, String>>()
        var onWrite: (UUID, ByteArray) -> Unit = { _, _ -> }

        override suspend fun read(characteristic: UUID): ByteArray =
            values[characteristic] ?: throw IOException("characteristic $characteristic not found")

        override suspend fun write(characteristic: UUID, data: ByteArray) {
            writes += characteristic to data.joinToString("") { String.format("%02x", it) }
            onWrite(characteristic, data)
        }

        override suspend fun enableNotifications(characteristic: UUID) {}
    }

    private fun oclean(model: String, battery: Int? = 80, hardware: ByteArray = ascii("V1.0")) = FakeOclean(
        buildMap {
            put(MODEL_NUMBER, ascii(model))
            put(HARDWARE, hardware)
            put(FIRMWARE, ascii("1.0.0.33"))
            battery?.let { put(BATTERY, byteArrayOf(it.toByte())) }
        },
    )

    /** Opens a session on [link] whose brush answers the session request with [frames]. */
    private fun session(link: FakeOclean, vararg frames: ByteArray): OcleanSession {
        val s = OcleanSession(link, utc)
        link.onWrite = { uuid, data ->
            if (uuid == SESSION_REQUEST && data.contentEquals(OcleanCodec.QUERY_SESSIONS)) {
                frames.forEach { s.onNotification(SESSIONS, it) }
            }
            if (uuid == SESSION_REQUEST && data.contentEquals(OcleanCodec.QUERY_STATUS)) s.onNotification(REPLY, hex(status))
        }
        return s
    }

    // --- recognition and promises ---

    @Test
    fun `recognizes Oclean by name or service`() {
        assertTrue(OcleanDriver.recognizes(Advertisement("Oclean X Pro")))
        assertTrue(OcleanDriver.recognizes(Advertisement("OCLEAN X")))
        assertTrue(OcleanDriver.recognizes(Advertisement(null, listOf(OcleanCodec.SERVICE))))
        assertFalse(OcleanDriver.recognizes(Advertisement("Oral-B Toothbrush")))
    }

    @Test
    fun `is experimental and changes nothing`() = runTest {
        assertEquals(SupportLevel.EXPERIMENTAL, OcleanDriver.supportLevel)
        assertFalse(OcleanDriver.changesBrush)
        assertFalse(OcleanDriver.passive)
        assertTrue(OcleanDriver.capabilities.modes.isEmpty())
        assertTrue(OcleanDriver.capabilities.switches.isEmpty())
        assertEquals(SupportLevel.EXPERIMENTAL, OcleanDriver.capabilities.history)
        val s = OcleanDriver.open(oclean("OCLEANY3M"))
        assertEquals(CommandResult.Unsupported, s.execute(BrushCommand.SetSwitch(Switch.MOTOR, true)))
    }

    // --- a type-1 brush ---

    @Test
    fun `an Oclean X shows model, firmware, battery and its newest session`() = runTest {
        val link = oclean("OCLEANY3M")
        val s = session(link, hex(inline2min))
        s.start()
        val state = s.state.value
        assertEquals("Oclean X", state.modelName)
        assertEquals("1.0.0.33", state.firmwareVersion)
        assertEquals(80, state.batteryPercent)
        val r = state.history.single()
        assertEquals(Instant.parse("2026-02-21T16:44:28Z"), r.startedAt)
        assertEquals(120, r.brushedSeconds)
        assertEquals(120, r.targetSeconds)
        assertEquals(1, r.number)
        assertNull(state.brushingScore) // the short record has no score
    }

    @Test
    fun `it sends only the session request, never 0202 and no clock`() = runTest {
        val link = oclean("OCLEANY3M")
        session(link, hex(inline2min)).start()
        assertEquals(listOf(SESSION_REQUEST to "0307"), link.writes)
    }

    @Test
    fun `without 2A19 the battery comes from the status reply`() = runTest {
        val link = oclean("OCLEANY3", battery = null)
        val s = session(link, hex(inline2min))
        s.start()
        assertEquals(29, s.state.value.batteryPercent)
        assertEquals("Oclean X Pro", s.state.value.modelName)
        assertEquals(listOf(SESSION_REQUEST to "0303", SESSION_REQUEST to "0307"), link.writes)
    }

    @Test
    fun `a count of 1 with only the short record is read once the brush goes quiet`() = runTest {
        val s = session(oclean("OCLEANY3M"), hex(countOneShort))
        s.start()
        val r = s.state.value.history.single()
        assertEquals(Instant.parse("2026-02-24T21:53:19Z"), r.startedAt)
        assertEquals(150, r.brushedSeconds)
    }

    @Test
    fun `full records over several notifications, newest first, score of the newest`() = runTest {
        val older = record(day = 4, minute = 10, target = 120, valid = 8, score = 1) // aborted after 8 s
        val newer = record(day = 5, minute = 20, target = 150, valid = 150, score = 97)
        val s = session(oclean("OCLEANY3M"), *packets(older, newer).toTypedArray())
        s.start()
        val history = s.state.value.history
        assertEquals(listOf(2, 1), history.map { it.number })
        assertEquals(Instant.parse("2026-03-05T07:20:00Z"), history[0].startedAt)
        assertEquals(150, history[0].brushedSeconds)
        assertEquals(8, history[1].brushedSeconds)
        assertEquals(120, history[1].targetSeconds)
        assertEquals(97, s.state.value.brushingScore)
    }

    @Test
    fun `a real full record, over several notifications`() = runTest {
        val s = session(oclean("OCLEANY3M"), *packets(hex(record8s)).toTypedArray())
        s.start()
        val r = s.state.value.history.single()
        assertEquals(Instant.parse("2026-09-17T20:13:50Z"), r.startedAt)
        assertEquals(8, r.brushedSeconds)
        assertEquals(120, r.targetSeconds)
        assertEquals(1, s.state.value.brushingScore)
    }

    @Test
    fun `no answer leaves the history empty`() = runTest {
        val s = session(oclean("OCLEANY3M"))
        s.start()
        assertTrue(s.state.value.history.isEmpty())
        assertEquals(80, s.state.value.batteryPercent)
    }

    // --- other Oclean brushes: standard characteristics only ---

    @Test
    fun `brushes of other protocols get no command at all`() = runTest {
        val link = oclean("OCLEANY3P")
        val s = session(link, hex(inline2min))
        s.start()
        assertTrue(link.writes.isEmpty())
        assertEquals("Oclean X Pro Elite", s.state.value.modelName)
        assertEquals(80, s.state.value.batteryPercent)
        assertTrue(s.state.value.history.isEmpty())
    }

    @Test
    fun `a model code in the hardware revision wins over the model number`() = runTest {
        val link = oclean("OCLEANY3", hardware = ascii("HH") + byteArrayOf(0x00, 0x04, 0x00, 0x01))
        val s = session(link)
        s.start()
        assertEquals("Oclean 0004", s.state.value.modelName)
        assertTrue(link.writes.isEmpty())
    }

    // --- codec details ---

    @Test
    fun `model codes are read only after HH, and 0000 means none`() {
        assertEquals("000C", OcleanCodec.modelCode(ascii("HH") + byteArrayOf(0x00, 0x0C, 0x00, 0x02)))
        assertNull(OcleanCodec.modelCode(ascii("HH") + byteArrayOf(0, 0, 0, 1)))
        assertNull(OcleanCodec.modelCode(ascii("V1.0.0")))
        assertNull(OcleanCodec.modelCode(ascii("HH")))
    }

    @Test
    fun `empty slots, impossible dates and a garbled count give no session`() {
        val stream = OcleanCodec.SessionStream(utc)
        assertEquals(emptyList<Any>(), stream.accept(hex("03072a42230000000215102c1c00007800780201"))) // year 0
        assertEquals(emptyList<Any>(), stream.accept(hex("03072a422300001a0d15102c1c00007800780201"))) // month 13
        assertEquals(emptyList<Any>(), stream.accept(hex("03072a4223ffff1a02")))
        assertNull(stream.accept(hex("1a0215102c1c")))  // no transfer under way
        assertNull(stream.flush())
    }

    @Test
    fun `status replies other than 0303 carry no battery`() {
        assertEquals(29, OcleanCodec.statusBattery(hex(status)))
        assertNull(OcleanCodec.statusBattery(hex("0302020e4b1d0000")))
        assertNull(OcleanCodec.statusBattery(hex("0303020eff")))
    }
}
