package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Access
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.DurationSpec
import io.github.damnms.buerstli.domain.ModeSpec
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.ParameterSpec
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.domain.ToothbrushDriver

/** Laifen Wave, first generation (LFTB01). Newer LFTB02 models use a different protocol. */
object LaifenWaveV1Driver : ToothbrushDriver {
    override val model = BrushModel(brand = "Laifen", name = "Wave (LFTB01)")
    override val supportLevel = SupportLevel.VERIFIED

    private fun mode(index: Int, strength: IntRange, hf: Boolean = false) = ModeSpec(
        index = index,
        parameters = listOf(
            ParameterSpec(STRENGTH, strength),
            ParameterSpec(RANGE, 1..10),
            ParameterSpec(SPEED, 1..10),
        ),
        highFrequency = hf,
    )

    override val capabilities = Capabilities(
        modes = listOf(
            mode(0, 1..10),
            mode(1, 1..10),
            mode(2, 1..10),
            mode(3, 11..20, hf = true),
        ),
        brushingDuration = DurationSpec(seconds = 60..300, stepSeconds = 30),
        switches = mapOf(
            Switch.REMINDER_30S to Access.READ_WRITE,
            Switch.HIGH_FREQUENCY to Access.READ_WRITE,
            Switch.MOTOR to Access.READ_WRITE,
            // The flight-mode command was never tried on a real brush, and if it cuts Bluetooth
            // we could lock ourselves out. Shown, not changed.
            Switch.FLIGHT_MODE to Access.READ_ONLY,
        ),
    )

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.name?.startsWith("LFTB01") == true

    override fun open(link: BrushLink): BrushSession = LaifenWaveV1Session(link, capabilities)
}
