package io.github.damnms.buerstli.drivers.oralb

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

/** Oral-B brushes that broadcast their state (iO, Genius, Smart, Pro, Triumph). Listens only. */
object OralBPassiveDriver : ToothbrushDriver {
    override val model = BrushModel(brand = "Oral-B", name = "Bluetooth toothbrush")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(modes = emptyList())
    override val passive = true

    override fun recognizes(advertisement: Advertisement): Boolean =
        OralBCodec.MANUFACTURER_ID in advertisement.manufacturerData

    override fun open(link: BrushLink): BrushSession = OralBSession()
}

private class OralBSession : BrushSession {
    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    override suspend fun start() {}

    override fun onNotification(characteristic: UUID, value: ByteArray) {}

    override fun onAdvertisement(advertisement: Advertisement) {
        val data = advertisement.manufacturerData[OralBCodec.MANUFACTURER_ID] ?: return
        OralBCodec.decode(data)?.let { _state.value = it }
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
