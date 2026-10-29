package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Intensity
import io.github.damnms.buerstli.domain.ModeName
import java.util.UUID

/**
 * Philips Sonicare "classic GATT": one characteristic per value, little-endian integers.
 * From philips_sonicare_ble/docs/BLE_PROTOCOL.md (MIT), which was verified by its authors on
 * DiamondClean, Prestige, ExpertClean, Kids and other models. Not yet tried with Bürstli.
 */
object SonicareCodec {
    private fun philips(short: Int) = UUID.fromString(String.format("477ea600-a260-11e4-ae37-0002a5d5%04x", short))
    private fun sig(short: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val SERVICE: UUID = philips(0x0001)
    val CONDOR_SERVICE: UUID = UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")

    val BATTERY: UUID = sig(0x2A19)
    val HANDLE_STATE: UUID = philips(0x4010)
    val BRUSHING_MODE: UUID = philips(0x4080)
    val BRUSHING_STATE: UUID = philips(0x4082)
    val BRUSHING_TIME: UUID = philips(0x4090)
    val ROUTINE_LENGTH: UUID = philips(0x4091)
    val INTENSITY: UUID = philips(0x40B0)
    val HEAD_LIMIT: UUID = philips(0x4280)
    val HEAD_USAGE: UUID = philips(0x4290)

    fun u8(v: ByteArray): Int? = if (v.isNotEmpty()) v[0].toInt() and 0xFF else null
    fun u16(v: ByteArray): Int? = if (v.size >= 2) (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8) else null

    fun mode(v: Int): ModeName? = when (v) {
        0 -> ModeName.CLEAN
        1 -> ModeName.WHITE_PLUS
        2 -> ModeName.GUM_HEALTH
        3 -> ModeName.DEEP_CLEAN_PLUS
        4 -> ModeName.SENSITIVE
        5 -> ModeName.TONGUE_CARE
        else -> null
    }

    fun intensity(v: Int): Intensity? = when (v) {
        0 -> Intensity.LOW
        1 -> Intensity.MEDIUM
        2 -> Intensity.HIGH
        else -> null
    }

    /**
     * Handle state (0 off, 1 standby, 2 run, 3 charge, 4 shutdown, 6 validate, 7 background)
     * combined with the brushing state (2 = paused), which not every model has.
     */
    fun activity(handleState: Int?, brushingState: Int?): Activity? = when {
        // Charging and off win: a paused session doesn't matter once the brush is on its stand.
        handleState == 3 -> Activity.CHARGING
        handleState == 0 || handleState == 4 -> Activity.OFF
        brushingState == 2 -> Activity.PAUSED
        handleState == 2 -> Activity.BRUSHING
        handleState == 1 || handleState == 6 || handleState == 7 -> Activity.STANDBY
        else -> null
    }

    fun wearPercent(usage: Int?, limit: Int?): Int? =
        if (usage == null || limit == null || limit <= 0) null else (usage * 100 / limit).coerceIn(0, 100)
}
