package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.Recognition
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveProDriver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveV1Driver
import io.github.damnms.buerstli.drivers.oralb.OralBPassiveDriver
import io.github.damnms.buerstli.drivers.sonicare.SonicareClassicDriver
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
    fun `Laifen LFTB02 gets the experimental Wave Pro driver`() {
        assertEquals(Recognition.Supported(LaifenWaveProDriver), recognize(Advertisement("LFTB02-S-412B")))
    }

    @Test
    fun `other Laifen models are recognized but unsupported`() {
        assertEquals(Recognition.Unsupported("Laifen"), recognize(Advertisement("LFTB01-SE-0001")))
    }

    @Test
    fun `classic Sonicare gets the experimental driver`() {
        val sonicare = Recognition.Supported(SonicareClassicDriver)
        assertEquals(sonicare, recognize(Advertisement("Philips Sonicare")))
        assertEquals(sonicare, recognize(Advertisement("Philips OHC")))
        assertEquals(
            sonicare,
            recognize(Advertisement(null, listOf(UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")))),
        )
    }

    @Test
    fun `Sonicare 7100 (Condor only) and unnamed Philips devices stay recognized but unsupported`() {
        val condor = UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")
        assertEquals(
            Recognition.Unsupported("Philips Sonicare"),
            recognize(Advertisement("Philips Sonicare", listOf(condor))),
        )
        assertEquals(
            Recognition.Unsupported("Philips Sonicare"),
            recognize(Advertisement(null, manufacturerData = mapOf(477 to byteArrayOf(1)))),
        )
    }

    @Test
    fun `Oral-B with broadcast data gets the passive driver, by name alone it stays unsupported`() {
        assertEquals(
            Recognition.Supported(OralBPassiveDriver),
            recognize(Advertisement(null, manufacturerData = mapOf(0x00DC to byteArrayOf(2, 1, 8, 2, 0x20, 0, 0, 1, 1, 0, 4)))),
        )
        assertEquals(Recognition.Unsupported("Oral-B"), recognize(Advertisement("Oral-B Toothbrush")))
    }

    @Test
    fun `Oclean is recognized by name or service, no driver yet`() {
        assertEquals(Recognition.Unsupported("Oclean"), recognize(Advertisement("Oclean X Pro")))
        assertEquals(
            Recognition.Unsupported("Oclean"),
            recognize(Advertisement(null, listOf(UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc18")))),
        )
    }

    private val xiaomi = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

    /** MiBeacon service data: frame control (2 bytes), product id (2 bytes, LE), … */
    private fun miBeacon(productId: Int) =
        Advertisement(null, serviceData = mapOf(xiaomi to byteArrayOf(0x30, 0x58, productId.toByte(), (productId shr 8).toByte(), 0x01)))

    @Test
    fun `Xiaomi toothbrushes are recognized by their product id`() {
        listOf(0x0489, 0x0806, 0x1790).forEach {
            assertEquals(Recognition.Unsupported("Xiaomi"), recognize(miBeacon(it)))
        }
    }

    @Test
    fun `other Xiaomi devices are not toothbrushes`() {
        assertEquals(Recognition.Unknown, recognize(miBeacon(0x045B))) // a temperature sensor
        assertEquals(Recognition.Unknown, recognize(Advertisement(null, serviceData = mapOf(xiaomi to byteArrayOf(0x30)))))
    }

    @Test
    fun `random devices are ignored`() {
        assertEquals(Recognition.Unknown, recognize(Advertisement("JBL Flip 5")))
    }
}
