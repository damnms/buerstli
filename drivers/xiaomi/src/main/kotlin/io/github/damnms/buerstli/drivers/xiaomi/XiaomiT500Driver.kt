package io.github.damnms.buerstli.drivers.xiaomi

import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Mi Smart Electric Toothbrush T500. Listens to its MiBeacons and never connects: brushing,
 * score, battery and brush head. Frames sent encrypted are skipped, Bürstli has no key for them.
 * Per ble_monitor the T500 only sends data after it was set up with Mi Home once.
 */
object XiaomiT500Driver : ToothbrushDriver {
    override val model = BrushModel(brand = "Xiaomi", name = "Mi Smart Electric Toothbrush T500")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(modes = emptyList())
    override val passive = true

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.serviceData[MiBeaconCodec.SERVICE]?.let(MiBeaconCodec::productId) == MiBeaconCodec.T500

    override fun open(link: BrushLink): BrushSession = XiaomiPassiveSession(MiBeaconCodec.T500)
}

internal class XiaomiPassiveSession(private val productId: Int) : BrushSession {
    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    override suspend fun start() {}

    override fun onNotification(characteristic: UUID, value: ByteArray) {}

    override fun onAdvertisement(advertisement: Advertisement) {
        val data = advertisement.serviceData[MiBeaconCodec.SERVICE] ?: return
        val beacon = MiBeaconCodec.parse(data) ?: return
        if (beacon.productId != productId || beacon.encrypted) return
        // Objects only overwrite what they report, so a repeated frame changes nothing.
        _state.value = beacon.objects.fold(_state.value, MiBeaconCodec::apply)
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
