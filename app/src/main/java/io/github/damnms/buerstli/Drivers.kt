package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.DriverRegistry
import io.github.damnms.buerstli.domain.KnownBrush
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

    val registry = DriverRegistry(
        drivers = listOf(
            LaifenWaveV1Driver,
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
            KnownBrush("Oral-B") { ad ->
                ad.name?.startsWith("Oral-B") == true || PROCTER_AND_GAMBLE_COMPANY_ID in ad.manufacturerData
            },
        ),
    )
}
