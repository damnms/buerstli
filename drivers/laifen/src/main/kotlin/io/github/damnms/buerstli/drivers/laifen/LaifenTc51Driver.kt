package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Access
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.domain.ToothbrushDriver

/**
 * Laifen kids' TC51 (LFTB01-C). Connects and listens, nothing is ever written. Its charging
 * base advertises on its own (TC51BB…) and is not claimed here.
 */
object LaifenTc51Driver : ToothbrushDriver {
    override val model = BrushModel(brand = "Laifen", name = "Kids TC51 (LFTB01-C)")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(
        // The status carries no mode values, only which mode is active.
        modes = emptyList(),
        switches = listOf(
            Switch.CHILD_LOCK, Switch.REMINDER_30S, Switch.HIGH_FREQUENCY, Switch.FLIGHT_MODE,
            Switch.POWER_RAMP_UP, Switch.SPIN_DRY, Switch.PRESSURE_WARNING,
        ).associateWith { Access.READ_ONLY },
    )

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.name?.startsWith("LFTB01-C") == true

    override fun open(link: BrushLink): BrushSession = LaifenListeningSession(link, LaifenTc51Codec::decode)
}
