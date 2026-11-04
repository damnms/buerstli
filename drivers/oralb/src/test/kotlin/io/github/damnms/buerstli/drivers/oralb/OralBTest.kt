package io.github.damnms.buerstli.drivers.oralb

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushingRecord
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ModeName
import io.github.damnms.buerstli.domain.NoLink
import io.github.damnms.buerstli.domain.SupportLevel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Real advertisements from the test suite of oralb-ble (MIT), the library behind Home Assistant's
 * Oral-B integration. Expected values are what oralb-ble itself decodes for the same bytes.
 */
class OralBTest {
    fun hex(s: String) = s.split("-").map { it.toInt(16).toByte() }.toByteArray()
    private fun decode(s: String) = OralBCodec.decode(hex(s))!!
    private fun ad(s: String) = Advertisement(name = "Oral-B Toothbrush", manufacturerData = mapOf(0x00DC to hex(s)))

    @Test
    fun `Triumph D36 while brushing`() {
        val s = decode("02-01-08-03-00-00-00-01-01-00-04")
        assertEquals("Triumph D36", s.modelName)
        assertEquals(Activity.BRUSHING, s.activity)
        assertEquals(ModeName.DAILY_CLEAN, s.modeName)
        assertEquals(0, s.brushingSeconds)
        assertEquals(false, s.pressureHigh)
        assertEquals(1, s.sector)
        assertEquals(4, s.sectorCount)
    }

    @Test
    fun `iO Series idle uses the iO mode table and hides the stale sector`() {
        val s = decode("06-32-6B-02-72-00-00-01-01-00-04")
        assertEquals("iO Series", s.modelName)
        assertEquals(Activity.STANDBY, s.activity)
        assertEquals(ModeName.SENSITIVE, s.modeName)
        assertNull(s.sector)
    }

    @Test
    fun `Genius D701 with high pressure in Pro Clean`() {
        val s = decode("03-22-0C-03-C0-01-30-07-04-3C-04")
        assertEquals("Genius Series D701", s.modelName)
        assertEquals(true, s.pressureHigh)
        assertEquals(108, s.brushingSeconds)
        assertEquals(ModeName.PRO_CLEAN, s.modeName) // oralb-ble says "turbo", the official app PRO_CLEAN
        assertEquals(4, s.sector)
    }

    @Test
    fun `sector 7 means the last sector, from the count or 4`() {
        assertEquals(6, decode("06-32-6E-03-52-01-28-00-07-00-06").sector)
        val noCount = decode("06-31-32-03-72-02-03-00-07-00-00")
        assertEquals(4, noCount.sector)
        assertNull(noCount.sectorCount)
        assertEquals(4, decode("06-32-6E-03-52-01-00-00-0C-00-06").sector) // upper bits are a display flag
    }

    @Test
    fun `a pressed button says nothing about pressure`() {
        assertNull(decode("06-31-32-03-78-02-03-00-02-00-06").pressureHigh)
        assertEquals(false, decode("06-31-32-03-70-02-03-00-02-00-06").pressureHigh)
    }

    @Test
    fun `other modes and menus`() {
        assertEquals(ModeName.SMART_ADAPT, decode("06-31-32-03-72-00-05-0B-01-00-06").modeName)
        val pro6000 = decode("01-02-05-03-00-00-08-04-01") // short form without sector count
        assertEquals(ModeName.WHITEN, pro6000.modeName)
        assertNull(pro6000.sectorCount)
        val menu = decode("08-36-52-08-32-00-00-05-00-01-00")
        assertEquals(Activity.STANDBY, menu.activity)
        assertEquals(ModeName.SUPER_SENSITIVE, menu.modeName)
    }

    // --- from the official app (see docs/ORAL-B.md): sound, but not seen on a real brush yet ---
    // Hand-made advertisements in the layout above; only the bytes under test differ.

    @Test
    fun `protocol 8 and later name the exact iO series in byte 9`() {
        assertEquals("iO Series 9", decode("08-36-52-08-32-00-00-05-00-01-00").modelName) // real, from oralb-ble
        assertEquals("iO Series 10", decode("08-36-52-02-00-00-00-00-00-10-04").modelName)
        assertEquals("iO Series 4", decode("09-34-52-02-00-00-00-00-00-06-04").modelName)
        assertEquals("iO Series", decode("08-36-52-02-00-00-00-00-00-63-04").modelName) // unknown series
        assertEquals("iO Series", decode("06-32-6B-02-72-00-00-01-01-01-04").modelName) // before protocol 8
    }

    @Test
    fun `the protocol version picks the mode table, so unknown iO models get iO names`() {
        val unknownIo = decode("07-7A-10-03-00-00-10-02-01-00-04")
        assertNull(unknownIo.modelName)
        assertEquals(ModeName.GUM_CARE, unknownIo.modeName)
        assertEquals(ModeName.MASSAGE, decode("05-7A-10-03-00-00-10-03-01-00-04").modeName)
    }

    @Test
    fun `Gentle White, and no mode name for off and settings`() {
        assertEquals(ModeName.GENTLE_WHITE, decode("06-32-6B-03-00-00-10-0C-01-00-04").modeName)
        assertNull(decode("06-32-6B-02-00-00-00-07-00-00-04").modeName) // iO "off"
        assertNull(decode("06-32-6B-02-00-00-00-08-00-00-04").modeName) // iO settings
        assertNull(decode("03-22-0C-02-00-00-00-00-00-00-04").modeName) // older brushes: "off"
    }

    @Test
    fun `newer iO models HCB`() {
        assertEquals("iO Series 5", decode("06-A5-10-02-00-00-00-00-00-00-04").modelName)
        assertEquals("iO Series", decode("06-A6-10-02-00-00-00-00-00-00-04").modelName)
    }

    @Test
    fun `states from the official app`() {
        assertEquals(Activity.PAUSED, decode("06-32-6B-09-00-00-30-00-02-00-04").activity)
        assertEquals(Activity.OFF, decode("06-32-6B-74-00-00-00-00-00-00-04").activity) // transport mode
        assertNull(decode("06-32-6B-07-00-00-00-00-00-00-04").activity) // on the charger, charging not allowed
    }

    @Test
    fun `too short or unknown values give nothing instead of nonsense`() {
        assertNull(OralBCodec.decode(hex("02-01-08-03-00-00-00-01")))
        assertNull(decode("02-01-08-03-00-00-00-63-01-00-04").modeName)
    }

    // --- driver and session ---

    @Test
    fun `recognizes Oral-B by its manufacturer data, is passive, experimental and read-only`() {
        assertTrue(OralBPassiveDriver.recognizes(ad("02-01-08-02-20-00-00-01-01-00-04")))
        assertFalse(OralBPassiveDriver.recognizes(Advertisement("Oral-B Toothbrush")))
        assertTrue(OralBPassiveDriver.passive)
        assertEquals(SupportLevel.EXPERIMENTAL, OralBPassiveDriver.supportLevel)
        assertTrue(OralBPassiveDriver.capabilities.modes.isEmpty())
    }

    @Test
    fun `the session lives on advertisements alone`() = runTest {
        val session = OralBPassiveDriver.open(NoLink).apply { start() }

        session.onAdvertisement(ad("02-01-08-02-20-00-00-01-01-00-04"))
        assertEquals(Activity.STANDBY, session.state.value.activity)

        session.onAdvertisement(ad("02-01-08-03-00-00-00-01-01-00-04"))
        assertEquals(Activity.BRUSHING, session.state.value.activity)

        session.onAdvertisement(Advertisement("Oral-B Toothbrush")) // no data: ignored
        assertEquals(Activity.BRUSHING, session.state.value.activity)

        assertEquals(CommandResult.Unsupported, session.execute(BrushCommand.ActivateMode(0)))
    }

    // --- battery over a short connection (layout from the official app, see docs/ORAL-B.md) ---

    @Test
    fun `battery layout depends on the protocol version`() {
        assertEquals(75, OralBCodec.battery(2, hex("4B")))
        assertEquals(75, OralBCodec.battery(6, hex("CB-00-3C-00"))) // top bit is not part of the level
        assertEquals(75, OralBCodec.battery(8, hex("CB-3C-00")))
        assertNull(OralBCodec.battery(2, hex("BD"))) // 189 %: nonsense, not a level
        assertNull(OralBCodec.battery(8, ByteArray(0)))
    }

    /** Answers reads from [values]; records everything, so tests can check nothing is written. */
    private class FakeOralB(val values: Map<UUID, ByteArray> = emptyMap()) : BrushLink {
        val reads = mutableListOf<UUID>()
        var writes = 0
        override suspend fun read(characteristic: UUID): ByteArray {
            reads += characteristic
            return values[characteristic] ?: throw IOException("not found")
        }
        override suspend fun write(characteristic: UUID, data: ByteArray) {
            writes++
            throw IOException("not found")
        }
        override suspend fun enableNotifications(characteristic: UUID) { writes++ }
    }

    private val standby = "08-36-52-02-00-00-00-05-00-01-04"
    private val brushing = "08-36-52-03-00-00-10-05-01-01-04"
    private val paused = "08-36-52-09-00-00-10-05-01-01-04"

    @Test
    fun `wants a short connection for the battery only while the brush is idle`() = runTest {
        val session = OralBPassiveDriver.open(NoLink).apply { start() }
        assertFalse(session.wantsPoll) // nothing heard yet
        session.onAdvertisement(ad(brushing))
        assertFalse(session.wantsPoll)
        session.onAdvertisement(ad(paused))
        assertFalse(session.wantsPoll)
        session.onAdvertisement(ad(standby))
        assertTrue(session.wantsPoll)
    }

    @Test
    fun `a brush without readable history still gives its battery, and keeps it`() = runTest {
        val session = OralBPassiveDriver.open(NoLink).apply { start() }
        session.onAdvertisement(ad(standby))
        val link = FakeOralB(mapOf(OralBCodec.BATTERY to hex("CB-3C-00")))

        try {
            session.poll(link)
            throw AssertionError("expected IOException for the missing history")
        } catch (_: IOException) {
        }

        assertEquals(listOf(OralBCodec.BATTERY), link.reads) // the history read failed at its write
        assertEquals(75, session.state.value.batteryPercent)
        assertTrue(session.wantsPoll) // the history is still missing
        session.onAdvertisement(ad(brushing)) // advertisements don't wipe the battery
        assertEquals(75, session.state.value.batteryPercent)
        assertEquals(Activity.BRUSHING, session.state.value.activity)
    }

    @Test(expected = IOException::class)
    fun `a failed read is reported to the adapter`() = runTest {
        val session = OralBPassiveDriver.open(NoLink).apply { start() }
        session.onAdvertisement(ad(standby))
        session.poll(FakeOralB())
    }

    // --- brushing history over the same short connection (official app, see docs/ORAL-B.md) ---
    // Hand-made sessions in the app's layout.

    /** 2026-11-04 07:00:00 UTC in the brush's clock: seconds since 2000-01-01. */
    private val time = "70-94-7D-32"

    @Test
    fun `session layout of protocol 8 and later`() {
        // id 0x123 (user 1) · set 2:00, 4 sectors · brushed 2:05 · high pressure 3.5 s · … · Gum Care · battery 80 %
        val r = OralBCodec.session(8, hex("$time-23-21-78-80-7D-00-23-00-00-00-00-00-02-00-01-02-50-00-00"))!!
        assertEquals(BrushingRecord(
            number = 0x123, brushedSeconds = 125, startedAt = Instant.parse("2026-11-04T07:00:00Z"),
            modeName = ModeName.GUM_CARE, targetSeconds = 120, highPressureSeconds = 4,
        ), r)
        assertNull(OralBCodec.session(8, hex("$time-23-21-78-80-7D-00-23-00-00-00-00-00-02-00-01-02-50-00"))) // 22 bytes
    }

    @Test
    fun `session layout up to protocol 5`() {
        // brushed 2:00 · 1 start · Daily Clean · high pressure 12 (x10 up to protocol 3) · … · set 2:00 · id 7
        val r = OralBCodec.session(3, hex("$time-78-00-01-01-0C-00-02-50-78-80-07-00"))!!
        assertEquals(BrushingRecord(
            number = 7, brushedSeconds = 120, startedAt = Instant.parse("2026-11-04T07:00:00Z"),
            modeName = ModeName.DAILY_CLEAN, targetSeconds = 120, highPressureSeconds = 12,
        ), r)
        assertEquals(1, OralBCodec.session(4, hex("$time-78-00-01-01-0C-00-02-50-78-80-07-00"))!!.highPressureSeconds)
    }

    @Test
    fun `no session behind id 0 or the all-0x44 filler, and no date from a clock never set`() {
        assertNull(OralBCodec.session(3, hex("$time-78-00-01-01-0C-00-02-50-78-80-00-00")))
        assertNull(OralBCodec.session(3, ByteArray(16) { 0x44 }))
        assertNull(OralBCodec.session(3, hex("10-00-00-00-78-00-01-01-0C-00-02-50-78-80-07-00"))!!.startedAt)
    }

    @Test
    fun `selecting a session is the only thing ever written, and only in its one form`() {
        assertEquals("02-00", OralBCodec.selectSession(0).joinToString("-") { "%02X".format(it) })
        assertEquals("02-1D", OralBCodec.selectSession(29).joinToString("-") { "%02X".format(it) })
        listOf(-1, 30, 52).forEach { i ->
            try {
                OralBCodec.selectSession(i)
                throw AssertionError("index $i must be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** A brush with [count] sessions (newest has id [count]), battery 80 %; checks every write. */
    private class FakeHistory(val count: Int, val failAt: Int? = null) : BrushLink {
        var selected: Int? = null
        val writes = mutableListOf<String>()
        override suspend fun read(characteristic: UUID): ByteArray = when (characteristic) {
            OralBCodec.BATTERY -> byteArrayOf(0x50, 0, 0)
            OralBCodec.SESSION_DATA -> {
                val i = selected ?: throw IOException("nothing selected")
                if (i == failAt) throw IOException("gone")
                val id = if (i < count) count - i else 0
                OralBTest().hex("70-94-7D-32-%02X-00-78-80-78-00-00-00-00-00-00-00-00-00-01-00-50-00-00".format(id))
            }
            else -> throw IOException("not readable here")
        }
        override suspend fun write(characteristic: UUID, data: ByteArray) {
            val bytes = data.joinToString("-") { "%02X".format(it) }
            writes += bytes
            check(characteristic == OralBCodec.CONFIGURE) { "write to $characteristic" }
            check(data.size == 2 && data[0] == 0x02.toByte()) { "unexpected write $bytes" }
            selected = data[1].toInt()
        }
        override suspend fun enableNotifications(characteristic: UUID) = throw AssertionError("no subscriptions")
    }

    @Test
    fun `the short connection reads battery and history, newest first, until the brush has no more`() = runTest {
        val session = OralBPassiveDriver.open(NoLink).apply { start() }
        session.onAdvertisement(ad(standby))
        val link = FakeHistory(count = 3)

        session.poll(link)

        assertEquals(listOf("02-00", "02-01", "02-02", "02-03"), link.writes)
        assertEquals(listOf(3, 2, 1), session.state.value.history.map { it.number })
        assertEquals(80, session.state.value.batteryPercent)
        assertFalse(session.wantsPoll)
        session.onAdvertisement(ad(brushing))
        assertEquals(3, session.state.value.history.size) // advertisements don't wipe it
    }

    @Test
    fun `at most 30 sessions, and a later failure keeps what was read`() = runTest {
        val full = OralBPassiveDriver.open(NoLink).apply { start(); onAdvertisement(ad(standby)) }
        val link = FakeHistory(count = 100)
        full.poll(link)
        assertEquals(30, full.state.value.history.size)
        assertEquals("02-1D", link.writes.last())

        val partial = OralBPassiveDriver.open(NoLink).apply { start(); onAdvertisement(ad(standby)) }
        partial.poll(FakeHistory(count = 5, failAt = 2))
        assertEquals(2, partial.state.value.history.size)
        assertFalse(partial.wantsPoll)
    }

    @Test
    fun `if not even the first session comes, the adapter hears about it and may try again`() = runTest {
        val session = OralBPassiveDriver.open(NoLink).apply { start(); onAdvertisement(ad(standby)) }
        try {
            session.poll(FakeHistory(count = 5, failAt = 0))
            throw AssertionError("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(80, session.state.value.batteryPercent)
        assertTrue(session.wantsPoll)
    }

    @Test
    fun `the driver says it can read a history`() {
        assertEquals(SupportLevel.EXPERIMENTAL, OralBPassiveDriver.capabilities.history)
    }
}
