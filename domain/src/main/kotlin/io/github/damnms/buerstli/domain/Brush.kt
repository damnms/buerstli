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
    val flightMode: Boolean? = null,
    val brushingDurationSeconds: Int? = null,
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
