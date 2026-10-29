package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.ToothbrushDriver

/** Philips Sonicare models with the classic GATT protocol. Read-only until someone confirms it. */
object SonicareClassicDriver : ToothbrushDriver {
    override val model = BrushModel(brand = "Philips", name = "Sonicare")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(modes = emptyList())
    override val mayNeedPairing = true

    override fun recognizes(advertisement: Advertisement): Boolean {
        // The 7100 series only offers the different "Condor" protocol.
        if (SonicareCodec.CONDOR_SERVICE in advertisement.serviceUuids &&
            SonicareCodec.SERVICE !in advertisement.serviceUuids
        ) return false
        val name = advertisement.name
        return SonicareCodec.SERVICE in advertisement.serviceUuids ||
            (name != null && ("Sonicare" in name || name.startsWith("Philips OHC")))
    }

    override fun open(link: BrushLink): BrushSession = SonicareSession(link)
}
