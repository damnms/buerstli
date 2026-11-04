package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.DriverRegistry
import io.github.damnms.buerstli.domain.KnownBrush
import io.github.damnms.buerstli.drivers.laifen.LaifenTc51Driver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveProDriver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveSeDriver
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveV1Driver
import io.github.damnms.buerstli.drivers.oralb.OralBPassiveDriver
import io.github.damnms.buerstli.drivers.sonicare.SonicareClassicDriver
import io.github.damnms.buerstli.drivers.xiaomi.XiaomiT500Driver
import java.util.UUID

/** Composition root: every supported toothbrush is registered here. */
object Drivers {
    // Identifiers from the protocol documentation of philips_sonicare_ble and oralb-ha.
    private val SONICARE_CLASSIC = UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")
    private val SONICARE_CONDOR = UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")
    private const val PHILIPS_COMPANY_ID = 477
    private const val PROCTER_AND_GAMBLE_COMPANY_ID = 0x00DC

    // Xiaomi: every Xiaomi device sends MiBeacon service data under 0xFE95; bytes 2-3 are the
    // product id (little-endian). The ids are the "pd_id" of every toothbrush in Mi Home's device
    // catalog (docs/XIAOMI.md); they match the ids known from ble_monitor and xiaomi-ble#356.
    private val XIAOMI_SERVICE = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    private val XIAOMI_TOOTHBRUSHES = setOf(
        // Xiaomi / Mijia, in the EU catalog (the T500 has a driver, which comes first)
        0x01A1, // soocare.toothbrush.m1
        0x0489, // soocare.toothbrush.m1s (T500)
        0x1790, // k0918.toothbrush.t700i (T700)
        0x3B94, // soocare.toothbrush.2501 (T501)
        0x7B27, // xiaomi.toothbrush.24peu (Servo-oscillation Pro)
        0x7B2A, // xiaomi.toothbrush.24pgl (Servo-oscillation Pro)
        0x8E95, // xiaomi.toothbrush.p02gl (Multi-oscillation Pro)
        // Xiaomi / Mijia, other regions only
        0x0440, // soocare.toothbrush.mc1 (Mitu kids)
        0x0806, // k0918.toothbrush.t700
        0x10C5, // soocare.toothbrush.t501
        0x1FF3, // soocare.toothbrush.1501 (T501)
        0x4DD8, // xiaomi.toothbrush.p001
        0x6800, // xiaomi.toothbrush.24p
        0x6F5B, // xiaomi.toothbrush.p002
        0x759B, // xiaomi.toothbrush.m04 (kids Pro)
        0x7B29, // xiaomi.toothbrush.24pkr
        // Partner brands sold through Mi Home, China catalog only
        0x00CE, // soocare.toothbrush.x3 (Soocas)
        0x05A1, // k0918.toothbrush.kid01 (Dr.Bei kids)
        0x1767, // nancii.toothbrush.t600
        0x2A24, // eizi.toothbrush.sy01
        0x3097, // xmlong.toothbrush.r02
        0x30C6, // xmlong.toothbrush.r05
        0x3228, // jrj.toothbrush.j3m800
        0x33E2, // xjyd.toothbrush.k1
        0x342F, // hlznkj.toothbrush.se40a
    )

    private fun xiaomiProductId(data: ByteArray?): Int? =
        if (data == null || data.size < 4) null else (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)

    // From the Oclean Home Assistant integration (Giallo1O/ha-oclean-integration).
    private val OCLEAN_SERVICE = UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc18")

    val registry = DriverRegistry(
        drivers = listOf(
            LaifenWaveV1Driver,
            LaifenWaveProDriver, // experimental, listens only
            LaifenWaveSeDriver, // experimental, listens only
            LaifenTc51Driver, // experimental, listens only
            SonicareClassicDriver, // experimental, read-only
            OralBPassiveDriver, // experimental, listens only
            XiaomiT500Driver, // experimental, listens only
        ),
        // Recognized, but no driver yet: Bürstli offers to record a report instead.
        knownUnsupported = listOf(
            KnownBrush("Laifen") { it.name?.startsWith("LFTB") == true },
            KnownBrush("Philips Sonicare") { ad ->
                ad.name?.let { "Sonicare" in it || it.startsWith("Philips OHC") } == true ||
                    SONICARE_CLASSIC in ad.serviceUuids || SONICARE_CONDOR in ad.serviceUuids ||
                    PHILIPS_COMPANY_ID in ad.manufacturerData
            },
            // Oclean: session data needs query commands. ADR-6 allows them for reading, but a driver
            // waits for an owner who tests it. Reports first, to see what the brush tells unasked.
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
