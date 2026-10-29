package io.github.damnms.buerstli.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationSpecTest {
    private val spec = DurationSpec(seconds = 60..300, stepSeconds = 30)

    @Test
    fun `durations on the grid are valid`() {
        listOf(60, 120, 150, 300).forEach { assertEquals(Validation.Valid, spec.validate(it)) }
    }

    @Test
    fun `durations outside the range are rejected`() {
        assertEquals(Validation.InvalidDuration(30, 60..300, 30), spec.validate(30))
        assertEquals(Validation.InvalidDuration(330, 60..300, 30), spec.validate(330))
    }

    @Test
    fun `durations between steps are rejected`() {
        assertEquals(Validation.InvalidDuration(75, 60..300, 30), spec.validate(75))
    }
}
