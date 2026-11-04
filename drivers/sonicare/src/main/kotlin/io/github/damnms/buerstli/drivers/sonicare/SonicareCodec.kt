package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushingRecord
import io.github.damnms.buerstli.domain.Intensity
import io.github.damnms.buerstli.domain.ModeName
import java.time.Instant
import java.util.UUID

/**
 * Philips Sonicare "classic GATT": one characteristic per value, little-endian integers.
 * Sources: philips_sonicare_ble/docs/BLE_PROTOCOL.md (MIT) and the official app 10.21.1; every value
 * and where it comes from is in docs/SONICARE.md.
 */
object SonicareCodec {
    private fun philips(short: Int) = UUID.fromString(String.format("477ea600-a260-11e4-ae37-0002a5d5%04x", short))
    private fun sig(short: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val SERVICE: UUID = philips(0x0001)
    val CONDOR_SERVICE: UUID = UUID.fromString("e50ba3c0-af04-4564-92ad-fef019489de6")

    val BATTERY: UUID = sig(0x2A19)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val FIRMWARE: UUID = sig(0x2A26)
    val HANDLE_STATE: UUID = philips(0x4010)
    val BRUSHING_MODE: UUID = philips(0x4080)
    val BRUSHING_STATE: UUID = philips(0x4082)
    val BRUSHING_TIME: UUID = philips(0x4090)
    val ROUTINE_LENGTH: UUID = philips(0x4091)
    val INTENSITY: UUID = philips(0x40B0)
    val HEAD_LIMIT: UUID = philips(0x4280)
    val HEAD_USAGE: UUID = philips(0x4290)

    // Storage service (0004): the brushing history, as the app fetches it.
    val LATEST_SESSION: UUID = philips(0x40D0)
    val SESSION_COUNT: UUID = philips(0x40D2)
    val SESSION_TYPE: UUID = philips(0x40D5)
    val ACTIVE_SESSION: UUID = philips(0x40E0)
    val SESSION_DATA: UUID = philips(0x4100)
    val SESSION_CONTROL: UUID = philips(0x4110)

    fun u8(v: ByteArray): Int? = if (v.isNotEmpty()) v[0].toInt() and 0xFF else null
    fun u16(v: ByteArray, at: Int = 0): Int? =
        if (v.size >= at + 2) (v[at].toInt() and 0xFF) or ((v[at + 1].toInt() and 0xFF) shl 8) else null
    private fun u32(v: ByteArray, at: Int): Long? =
        if (v.size >= at + 4) (u16(v, at)!!.toLong()) or (u16(v, at + 2)!!.toLong() shl 16) else null

    /** Device Information strings, without the padding some firmwares add. */
    fun text(v: ByteArray): String? = String(v, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }.ifEmpty { null }

    // --- modes ---

    // The app's internal mode names, as Bürstli shows them (the names printed on each handle are in
    // the app's resources, which weren't studied).
    private val CLEAN = ModeName.CLEAN
    private val WHITE = ModeName.WHITE_PLUS
    private val GUM = ModeName.GUM_HEALTH
    private val DEEP = ModeName.DEEP_CLEAN_PLUS
    private val TONGUE = ModeName.TONGUE_CARE
    private val SENSITIVE = ModeName.SENSITIVE
    private val MAX = ModeName.MAX_CARE
    private val COMFORT = ModeName.COMFORT
    private val POLISH = ModeName.POLISH

    /** Modes by routine number (the app's list per model without its entry 0, Touch-up). */
    private val MODES_BY_MODEL: Map<String, List<ModeName>> = buildMap {
        put("HX741X", listOf(CLEAN, SENSITIVE, WHITE))
        listOf("HX742X", "HX74XX").forEach { put(it, listOf(CLEAN, SENSITIVE, WHITE, GUM)) }
        put("HX84XX", listOf(CLEAN, DEEP, WHITE, GUM, SENSITIVE))
        put("HX85XX", listOf(CLEAN, DEEP, WHITE, GUM, SENSITIVE, MAX))
        put("HX92XX", listOf(CLEAN, DEEP, WHITE, GUM, SENSITIVE, MAX, COMFORT))
        listOf("HX98XX", "HX9XXX").forEach { put(it, listOf(CLEAN, DEEP, WHITE, GUM, SENSITIVE, MAX, COMFORT, POLISH)) }
        listOf("HX9120", "HX961X").forEach { put(it, listOf(CLEAN, WHITE, DEEP)) }
        put("HX960X", listOf(CLEAN, GUM, DEEP))
        put("HX991X", listOf(CLEAN, WHITE, GUM, DEEP))
    }

    /** Every other model, and an unknown one: the app's default list. */
    private val DEFAULT_MODES = listOf(CLEAN, WHITE, GUM, DEEP, TONGUE, SENSITIVE, MAX, COMFORT)

    /** "xian" models count by routine id instead, without skipping an entry. */
    private val XIAN = setOf("HX9996", "HX999X")
    private val XIAN_MODES = listOf(CLEAN, WHITE, GUM, TONGUE, DEEP, SENSITIVE, MAX, COMFORT, POLISH)

    fun isXian(modelNumber: String?) = modelNumber in XIAN

    /**
     * The mode for a routine number from `4080` or a stored session, which means something else on
     * every model. The app shows Clean for numbers it doesn't know; Bürstli shows nothing.
     */
    fun mode(v: Int, modelNumber: String?): ModeName? {
        if (isXian(modelNumber)) return XIAN_MODES.getOrNull(v)
        if (v !in 0..7) return null
        return (MODES_BY_MODEL[modelNumber] ?: DEFAULT_MODES).getOrNull(v)
    }

    fun intensity(v: Int): Intensity? = when (v) {
        0 -> Intensity.LOW
        1 -> Intensity.MEDIUM
        2 -> Intensity.HIGH
        else -> null
    }

    /**
     * Handle state (0 off, 1 standby, 2 run, 3 charge, 4 shutdown, 6 validate, 7 background)
     * combined with the routine state (2 = paused), which not every model has.
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

    // --- brushing history ---

    /** Before 2015 the brush's clock was never set (the app sets it), so the time means nothing. */
    private const val CLOCK_SET_AFTER = 1_420_070_400L // 2015-01-01T00:00:00Z

    /**
     * One stored session: the first notification of that session on `4100`, 13, 16 or 18 bytes.
     * Bytes 1–4 start (seconds since 1970), 5–6 session id, 7–8 brushed seconds, 9–10 routine
     * length, 11 routine number, 12 intensity, 13 routine id (from 16 bytes; the mode on xian).
     */
    fun session(v: ByteArray, modelNumber: String?): BrushingRecord? {
        if (v.size < 13) return null
        val start = u32(v, 1)!!
        val routine = if (isXian(modelNumber)) (if (v.size >= 16) v[13].toInt() and 0xFF else null)
        else v[11].toInt() and 0xFF
        return BrushingRecord(
            number = u16(v, 5)!!,
            brushedSeconds = u16(v, 7)!!,
            startedAt = if (start >= CLOCK_SET_AFTER) Instant.ofEpochSecond(start) else null,
            modeName = routine?.let { mode(it, modelNumber) },
            targetSeconds = u16(v, 9)!!.takeIf { it > 0 },
        )
    }

    /**
     * The only messages Bürstli ever sends to a Sonicare, all to read the history the way the app
     * does. Nothing else can be built here, so nothing on the brush can change.
     */
    sealed class Request(val characteristic: UUID, val bytes: ByteArray) {
        /** Data type 0: brushing sessions. */
        data object ReadSessions : Request(SESSION_TYPE, byteArrayOf(0))

        class StartAt(sessionId: Int) :
            Request(ACTIVE_SESSION, byteArrayOf((sessionId and 0xFF).toByte(), ((sessionId shr 8) and 0xFF).toByte()))

        /** "All sessions from the one in [ACTIVE_SESSION] on." */
        data object SendAll : Request(SESSION_CONTROL, byteArrayOf(1))

        data object Cancel : Request(SESSION_CONTROL, byteArrayOf(2))
    }
}
