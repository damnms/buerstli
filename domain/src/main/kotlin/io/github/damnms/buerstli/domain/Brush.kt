package io.github.damnms.buerstli.domain

/** Which toothbrush model a driver talks to, e.g. Laifen / Wave (LFTB01). */
data class BrushModel(val brand: String, val name: String)

/**
 * How much a driver can be trusted. Only drivers tested on a real brush are VERIFIED;
 * everything built from documentation or captures alone stays EXPERIMENTAL.
 */
enum class SupportLevel { VERIFIED, EXPERIMENTAL }

/** What a model can do. The UI is built from this, never from brand-specific knowledge. */
data class Capabilities(
    val modes: List<ModeSpec>,
    /** Null if the brushing duration can't be changed on this model. */
    val brushingDuration: DurationSpec? = null,
    /** On/off settings the model reports, and whether Bürstli may change them. */
    val switches: Map<Switch, Access> = emptyMap(),
) {
    fun mode(index: Int): ModeSpec? = modes.firstOrNull { it.index == index }
}

/**
 * Snapshot of what the brush reported last. Every field is optional because brushes
 * differ in what they report, and a fresh connection knows nothing yet.
 */
data class BrushState(
    val batteryPercent: Int? = null,
    val activeModeIndex: Int? = null,
    val modes: Map<Int, ModeSettings> = emptyMap(),
    /** Target brushing time of a session. */
    val brushingDurationSeconds: Int? = null,
    val switches: Map<Switch, Boolean> = emptyMap(),
    /** What the brush is doing right now, for brushes that say so. */
    val activity: Activity? = null,
    /** Named brushing program, for brushes with named modes (e.g. Sonicare). */
    val modeName: ModeName? = null,
    val intensity: Intensity? = null,
    /** Seconds brushed in the current session. */
    val brushingSeconds: Int? = null,
    /** How worn the brush head is, 0-100. */
    val brushHeadWearPercent: Int? = null,
    /** True if the brush reports too much pressure right now. */
    val pressureHigh: Boolean? = null,
    /** Area of the mouth the brush guides you through right now (1-based), and how many there are. */
    val sector: Int? = null,
    val sectorCount: Int? = null,
    /** The exact model, for brushes that say it (e.g. "iO Series"). */
    val modelName: String? = null,
) {
    companion object {
        val UNKNOWN = BrushState()
    }
}

/** Allowed brushing durations, e.g. 60..300 s in 30 s steps. */
data class DurationSpec(val seconds: IntRange, val stepSeconds: Int) {
    fun validate(value: Int): Validation =
        if (value in seconds && (value - seconds.first) % stepSeconds == 0) Validation.Valid
        else Validation.InvalidDuration(value, seconds, stepSeconds)
}

/** On/off settings and states of a brush. */
enum class Switch {
    /** Short buzz every 30 s as a cue to move to the next zone. */
    REMINDER_30S,

    /** Unlocks the high-frequency mode. */
    HIGH_FREQUENCY,

    /** Travel lock. */
    FLIGHT_MODE,

    /** The motor is running, i.e. the brush is brushing. */
    MOTOR,

    // Laifen Wave Pro features
    DEEP_CLEAN,
    ANTI_SPLASH,
    POWER_RAMP_UP,
    SPIN_DRY,
    PRESSURE_WARNING,
    BRISTLE_PROTECTION,
    LIFT_TO_WAKE,
}

enum class Access { READ_ONLY, READ_WRITE }

enum class Activity { OFF, STANDBY, BRUSHING, PAUSED, CHARGING }

enum class ModeName {
    // Philips Sonicare
    CLEAN, WHITE_PLUS, GUM_HEALTH, DEEP_CLEAN_PLUS,
    // Oral-B
    DAILY_CLEAN, MASSAGE, WHITEN, DEEP_CLEAN, TURBO, GUM_CARE, INTENSE, SUPER_SENSITIVE, SMART_ADAPT,
    // both
    SENSITIVE, TONGUE_CARE,
}

enum class Intensity { LOW, MEDIUM, HIGH }
