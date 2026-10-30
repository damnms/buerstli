package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.DriverRegistry
import io.github.damnms.buerstli.domain.KnownBrush
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveProDriver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveV1Driver
import io.github.damnms.buerstli.drivers.oralb.OralBPassiveDriver
import io.github.damnms.buerstli.drivers.sonicare.SonicareClassicDriver
import java.util.UUID

/** Composition root: every supported toothbrush is registered here. */
object Drivers {
    // Identifiers from the protocol documentation of philips_sonicare_ble and oralb-ha.
    private val SONICARE_CLASSIC = UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")
    private val SONICARE_CONDOR = UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")
    private const val PHILIPS_COMPANY_ID = 477
    private const val PROCTER_AND_GAMBLE_COMPANY_ID = 0x00DC

    // Xiaomi: every Xiaomi device sends MiBeacon service data under 0xFE95; bytes 2-3 are the
    // product id (little-endian). Toothbrush ids from ble_monitor: M1S-T500, T700, T700i;
    // T501 from a real advertisement in Bluetooth-Devices/xiaomi-ble#356.
    private val XIAOMI_SERVICE = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    private val XIAOMI_TOOTHBRUSHES = setOf(0x0489, 0x0806, 0x1790, 0x3B94)

    private fun xiaomiProductId(data: ByteArray?): Int? =
        if (data == null || data.size < 4) null else (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)

    // From the Oclean Home Assistant integration (Giallo1O/ha-oclean-integration).
    private val OCLEAN_SERVICE = UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc18")

    val registry = DriverRegistry(
        drivers = listOf(
            LaifenWaveV1Driver,
            LaifenWaveProDriver, // experimental, listens only
            SonicareClassicDriver, // experimental, read-only
            OralBPassiveDriver, // experimental, listens only
        ),
        // Recognized, but no driver yet: Bürstli offers to record a report instead.
        knownUnsupported = listOf(
            KnownBrush("Laifen") { it.name?.startsWith("LFTB") == true },
            KnownBrush("Philips Sonicare") { ad ->
                ad.name?.let { "Sonicare" in it || it.startsWith("Philips OHC") } == true ||
                    SONICARE_CLASSIC in ad.serviceUuids || SONICARE_CONDOR in ad.serviceUuids ||
                    PHILIPS_COMPANY_ID in ad.manufacturerData
            },
            // Oclean: session data needs query commands, which experimental drivers don't send
            // (ADR-6). Reports first, to see what the brush tells without being asked.
            KnownBrush("Oclean") { ad ->
                ad.name?.startsWith("Oclean", ignoreCase = true) == true || OCLEAN_SERVICE in ad.serviceUuids
            },
            KnownBrush("Xiaomi") { ad -> xiaomiProductId(ad.serviceData[XIAOMI_SERVICE]) in XIAOMI_TOOTHBRUSHES },
            KnownBrush("Oral-B") { ad ->
                ad.name?.startsWith("Oral-B") == true || PROCTER_AND_GAMBLE_COMPANY_ID in ad.manufacturerData
            },
        ),
    )
}
