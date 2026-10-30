package io.github.damnms.buerstli.drivers.oralb

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
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

/**
 * Real advertisements from the test suite of oralb-ble (MIT), the library behind Home Assistant's
 * Oral-B integration. Expected values are what oralb-ble itself decodes for the same bytes.
 */
class OralBTest {
    private fun hex(s: String) = s.split("-").map { it.toInt(16).toByte() }.toByteArray()
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
    fun `Genius D701 with high pressure in turbo`() {
        val s = decode("03-22-0C-03-C0-01-30-07-04-3C-04")
        assertEquals("Genius Series D701", s.modelName)
        assertEquals(true, s.pressureHigh)
        assertEquals(108, s.brushingSeconds)
        assertEquals(ModeName.TURBO, s.modeName)
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
}
