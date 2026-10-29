package io.github.damnms.buerstli.domain

import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSpecTest {

    private val normal = ModeSpec(
        index = 0,
        parameters = listOf(
            ParameterSpec(STRENGTH, 1..10),
            ParameterSpec(RANGE, 1..10),
            ParameterSpec(SPEED, 1..10),
        ),
    )

    @Test
    fun `settings inside all ranges are valid`() {
        assertEquals(Validation.Valid, normal.validate(ModeSettings.of(STRENGTH to 1, RANGE to 10, SPEED to 5)))
    }

    @Test
    fun `value outside its range is rejected with the allowed range`() {
        assertEquals(
            Validation.OutOfRange(STRENGTH, 11, 1..10),
            normal.validate(ModeSettings.of(STRENGTH to 11, RANGE to 5, SPEED to 5)),
        )
    }

    @Test
    fun `missing parameter is rejected`() {
        assertEquals(
            Validation.MissingParameter(SPEED),
            normal.validate(ModeSettings.of(STRENGTH to 5, RANGE to 5)),
        )
    }

    @Test
    fun `parameter the mode does not have is rejected`() {
        val withoutSpeed = normal.copy(parameters = normal.parameters.filter { it.kind != SPEED })
        assertEquals(
            Validation.UnknownParameter(SPEED),
            withoutSpeed.validate(ModeSettings.of(STRENGTH to 5, RANGE to 5, SPEED to 5)),
        )
    }

    @Test
    fun `changing one value keeps the others`() {
        val s = ModeSettings.of(STRENGTH to 3, RANGE to 1, SPEED to 5).with(SPEED, 7)
        assertEquals(ModeSettings.of(STRENGTH to 3, RANGE to 1, SPEED to 7), s)
    }
}
