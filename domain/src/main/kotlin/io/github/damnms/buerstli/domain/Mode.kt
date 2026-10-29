package io.github.damnms.buerstli.domain

/** The knobs a brushing mode can have. Extend when a brand brings a new one. */
enum class ParameterKind { STRENGTH, RANGE, SPEED }

/** One knob of a mode and the values the brush accepts for it. */
data class ParameterSpec(val kind: ParameterKind, val range: IntRange)

/** What a mode looks like on a given model. */
data class ModeSpec(
    val index: Int,
    val parameters: List<ParameterSpec>,
    val highFrequency: Boolean = false,
) {
    fun spec(kind: ParameterKind): ParameterSpec? = parameters.firstOrNull { it.kind == kind }

    /** Checks settings against this mode before anything is sent to a brush. */
    fun validate(settings: ModeSettings): Validation {
        settings.values.keys.firstOrNull { spec(it) == null }?.let { return Validation.UnknownParameter(it) }
        for (p in parameters) {
            val value = settings[p.kind] ?: return Validation.MissingParameter(p.kind)
            if (value !in p.range) return Validation.OutOfRange(p.kind, value, p.range)
        }
        return Validation.Valid
    }
}

/** Value object: the values of one mode, e.g. strength 6, range 6, speed 5. */
data class ModeSettings(val values: Map<ParameterKind, Int>) {
    operator fun get(kind: ParameterKind): Int? = values[kind]
    fun with(kind: ParameterKind, value: Int): ModeSettings = ModeSettings(values + (kind to value))

    companion object {
        fun of(vararg values: Pair<ParameterKind, Int>) = ModeSettings(values.toMap())
    }
}

sealed interface Validation {
    data object Valid : Validation
    data class OutOfRange(val kind: ParameterKind, val value: Int, val allowed: IntRange) : Validation
    data class MissingParameter(val kind: ParameterKind) : Validation
    data class UnknownParameter(val kind: ParameterKind) : Validation
}
