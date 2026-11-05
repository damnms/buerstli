package io.github.damnms.buerstli.drivers.oclean

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.ToothbrushDriver

/**
 * Every Oclean brush. The advertisement doesn't tell the model, so the session reads it after
 * connecting and only talks the type-1 protocol to brushes known to speak it. Experimental and
 * read-only; see docs/OCLEAN.md.
 */
object OcleanDriver : ToothbrushDriver {
    override val model = BrushModel(brand = "Oclean", name = "Oclean")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(modes = emptyList(), history = SupportLevel.EXPERIMENTAL)

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.name?.startsWith("Oclean", ignoreCase = true) == true ||
            OcleanCodec.SERVICE in advertisement.serviceUuids

    override fun open(link: BrushLink): BrushSession = OcleanSession(link)
}
