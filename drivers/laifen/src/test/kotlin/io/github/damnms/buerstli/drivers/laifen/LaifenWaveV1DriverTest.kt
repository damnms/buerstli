package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.SupportLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaifenWaveV1DriverTest {

    private val driver = LaifenWaveV1Driver

    @Test
    fun `recognizes the Wave V1 by its Bluetooth name`() {
        assertTrue(driver.recognizes(Advertisement("LFTB01-P-1DEE")))
        assertTrue(driver.recognizes(Advertisement("LFTB01-T-0001")))
    }

    @Test
    fun `does not claim the newer Wave or Wave Pro, which speak another protocol`() {
        assertFalse(driver.recognizes(Advertisement("LFTB02-S-412B")))
        assertFalse(driver.recognizes(Advertisement("LFTB02-S-7857")))
    }

    @Test
    fun `does not claim unrelated devices`() {
        assertFalse(driver.recognizes(Advertisement("Oral-B Toothbrush")))
        assertFalse(driver.recognizes(Advertisement(null)))
    }

    @Test
    fun `is verified on real hardware`() {
        assertEquals(SupportLevel.VERIFIED, driver.supportLevel)
    }

    @Test
    fun `has three normal modes and a high-frequency mode with stronger vibration`() {
        val modes = driver.capabilities.modes
        assertEquals(4, modes.size)
        modes.take(3).forEach { assertEquals(1..10, it.spec(STRENGTH)!!.range) }
        assertTrue(modes[3].highFrequency)
        assertEquals(11..20, modes[3].spec(STRENGTH)!!.range)
    }
}
