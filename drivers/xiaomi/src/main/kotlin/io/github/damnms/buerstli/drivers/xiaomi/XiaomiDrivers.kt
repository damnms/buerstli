package io.github.damnms.buerstli.drivers.xiaomi

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.KeySpec
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.ToothbrushDriver

/**
 * Xiaomi brushes, passive: Bürstli listens to their MiBeacons and never connects or sends anything.
 * Encrypted broadcasts can be read with the brush's bindkey, which the user enters; see
 * docs/XIAOMI.md. Only models with a real frame known get a driver; the others are recognized.
 */
abstract class XiaomiPassiveDriver internal constructor(
    name: String,
    private val productIds: Set<Int>,
    /** True if the model is known to broadcast encrypted, so Bürstli asks for the key right away. */
    private val encrypted: Boolean,
) : ToothbrushDriver {
    override val model = BrushModel(brand = "Xiaomi", name = name)
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(modes = emptyList())
    override val passive = true
    override val key = KeySpec(bytes = 16, helpUrl = "https://github.com/damnms/buerstli/blob/main/docs/XIAOMI.md#bindkey")

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.serviceData[MiBeaconCodec.SERVICE]?.let(MiBeaconCodec::productId) in productIds

    override fun open(link: BrushLink): BrushSession = XiaomiPassiveSession(productIds, encrypted)
}

/** Mi Smart Electric Toothbrush T500 (soocare.toothbrush.m1s): broadcasts in clear. */
object XiaomiT500Driver : XiaomiPassiveDriver("Mi Smart Electric Toothbrush T500", setOf(MiBeaconCodec.T500), encrypted = false)

/** T700 (k0918.toothbrush.t700) and its global version T700i: encrypted, MiBeacon V5. */
object XiaomiT700Driver : XiaomiPassiveDriver("Electric Toothbrush T700", setOf(0x0806, 0x1790), encrypted = true)

/** Smart Electric Toothbrush T501, global version (soocare.toothbrush.2501): encrypted. */
object XiaomiT501Driver : XiaomiPassiveDriver("Smart Electric Toothbrush T501", setOf(0x3B94), encrypted = true)
