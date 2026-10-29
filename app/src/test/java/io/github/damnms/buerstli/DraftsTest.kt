package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftsTest {
    private val onBrush = ModeSettings.of(STRENGTH to 6, RANGE to 6, SPEED to 5)

    @Test
    fun `without edits the brush value is shown and nothing is dirty`() {
        val d = Drafts()
        assertEquals(onBrush, d.current(2, onBrush))
        assertFalse(d.isDirty(2, onBrush))
    }

    @Test
    fun `an edit starts from the brush value and only changes one knob`() {
        val d = Drafts().edit(2, onBrush, SPEED, 8)
        assertEquals(ModeSettings.of(STRENGTH to 6, RANGE to 6, SPEED to 8), d.current(2, onBrush))
        assertTrue(d.isDirty(2, onBrush))
    }

    @Test
    fun `editing back to the brush value is not dirty`() {
        val d = Drafts().edit(2, onBrush, SPEED, 8).edit(2, onBrush, SPEED, 5)
        assertFalse(d.isDirty(2, onBrush))
    }

    @Test
    fun `drafts of other modes are untouched`() {
        val d = Drafts().edit(2, onBrush, SPEED, 8).clear(1)
        assertTrue(d.isDirty(2, onBrush))
        assertFalse(Drafts().edit(2, onBrush, SPEED, 8).clear(2).isDirty(2, onBrush))
    }
}
