package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.Recognition
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveV1Driver
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class DriversTest {
    private fun recognize(ad: Advertisement) = Drivers.registry.recognize(ad)

    @Test
    fun `the tested Laifen Wave gets its driver`() {
        assertEquals(Recognition.Supported(LaifenWaveV1Driver), recognize(Advertisement("LFTB01-P-1DEE")))
    }

    @Test
    fun `other Laifen models are recognized but unsupported`() {
        assertEquals(Recognition.Unsupported("Laifen"), recognize(Advertisement("LFTB02-S-412B")))
        assertEquals(Recognition.Unsupported("Laifen"), recognize(Advertisement("LFTB01-SE-0001")))
    }

    @Test
    fun `Philips Sonicare by name, service or manufacturer id`() {
        val sonicare = Recognition.Unsupported("Philips Sonicare")
        assertEquals(sonicare, recognize(Advertisement("Philips Sonicare")))
        assertEquals(sonicare, recognize(Advertisement("Philips OHC")))
        assertEquals(
            sonicare,
            recognize(Advertisement(null, listOf(UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")))),
        )
        assertEquals(sonicare, recognize(Advertisement(null, manufacturerData = mapOf(477 to byteArrayOf(1)))))
    }

    @Test
    fun `Oral-B by name or manufacturer id`() {
        assertEquals(Recognition.Unsupported("Oral-B"), recognize(Advertisement("Oral-B Toothbrush")))
        assertEquals(
            Recognition.Unsupported("Oral-B"),
            recognize(Advertisement(null, manufacturerData = mapOf(0x00DC to byteArrayOf(1)))),
        )
    }

    @Test
    fun `random devices are ignored`() {
        assertEquals(Recognition.Unknown, recognize(Advertisement("JBL Flip 5")))
    }
}
