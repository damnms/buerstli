package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.Recognition
import io.github.damnms.buerstli.drivers.laifen.LaifenTc51Driver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveProDriver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveSeDriver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveV1Driver
import io.github.damnms.buerstli.drivers.oralb.OralBPassiveDriver
import io.github.damnms.buerstli.drivers.sonicare.SonicareClassicDriver
import io.github.damnms.buerstli.drivers.xiaomi.XiaomiT500Driver
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
    fun `Laifen LFTB01-SE gets the experimental Wave SE driver`() {
        assertEquals(Recognition.Supported(LaifenWaveSeDriver), recognize(Advertisement("LFTB01-SE-0001")))
    }

    @Test
    fun `Laifen LFTB01-C gets the experimental TC51 driver`() {
        assertEquals(Recognition.Supported(LaifenTc51Driver), recognize(Advertisement("LFTB01-C-0001")))
    }

    @Test
    fun `other Laifen models are recognized but unsupported`() {
        assertEquals(Recognition.Unsupported("Laifen"), recognize(Advertisement("LFTB03-X-0001")))
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
        // Every toothbrush pd_id in Mi Home's device catalog (docs/XIAOMI.md), except the T500.
        listOf(
            0x01A1, 0x1790, 0x3B94, 0x7B27, 0x7B2A, 0x8E95, // EU catalog
            0x0440, 0x0806, 0x10C5, 0x1FF3, 0x4DD8, 0x6800, 0x6F5B, 0x759B, 0x7B29, // other regions
            0x00CE, 0x05A1, 0x1767, 0x2A24, 0x3097, 0x30C6, 0x3228, 0x33E2, 0x342F, // partner brands
        ).forEach {
            assertEquals("product id 0x%04X".format(it), Recognition.Unsupported("Xiaomi"), recognize(miBeacon(it)))
        }
    }

    @Test
    fun `the Xiaomi T500 gets the experimental passive driver`() {
        assertEquals(Recognition.Supported(XiaomiT500Driver), recognize(miBeacon(0x0489)))
    }

    @Test
    fun `the sterilizing cup in Mi Home's toothbrush category is not a toothbrush`() {
        assertEquals(Recognition.Unknown, recognize(miBeacon(0x094A))) // zhij.toothbrush.bv1
    }

    @Test
    fun `the Xiaomi T501 is recognized from a real advertisement`() {
        // Frame control and product id (0x3B94) of a real T501 advertisement, from
        // Bluetooth-Devices/xiaomi-ble#356. Only these four bytes; the rest of the
        // owner's packet is left out.
        val t501 = byteArrayOf(0x48, 0x59, 0x94.toByte(), 0x3B)
        assertEquals(Recognition.Unsupported("Xiaomi"), recognize(Advertisement(null, serviceData = mapOf(xiaomi to t501))))
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
