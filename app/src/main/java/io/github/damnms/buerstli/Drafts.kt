package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind

/** Unsaved slider edits per mode. Survives the brush falling asleep. */
data class Drafts(private val byMode: Map<Int, ModeSettings> = emptyMap()) {

    fun current(mode: Int, onBrush: ModeSettings?): ModeSettings? = byMode[mode] ?: onBrush

    fun isDirty(mode: Int, onBrush: ModeSettings?): Boolean = byMode[mode]?.let { it != onBrush } ?: false

    fun edit(mode: Int, onBrush: ModeSettings, kind: ParameterKind, value: Int): Drafts {
        val base = byMode[mode] ?: onBrush
        return copy(byMode = byMode + (mode to base.with(kind, value)))
    }

    fun clear(mode: Int): Drafts = copy(byMode = byMode - mode)
}
