package io.github.damnms.buerstli.domain

/** Which toothbrush model a driver talks to, e.g. Laifen / Wave (LFTB01). */
data class BrushModel(val brand: String, val name: String)

/**
 * How much a driver can be trusted. Only drivers tested on a real brush are VERIFIED;
 * everything built from documentation or captures alone stays EXPERIMENTAL.
 */
enum class SupportLevel { VERIFIED, EXPERIMENTAL }

/** What a model can do. The UI is built from this, never from brand-specific knowledge. */
data class Capabilities(val modes: List<ModeSpec>) {
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
) {
    companion object {
        val UNKNOWN = BrushState()
    }
}
