package io.github.damnms.buerstli.drivers.oralb

import io.github.damnms.buerstli.domain.Activity
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
 * Oral-B brushes that broadcast their state (iO, Genius, Smart, Pro, Triumph). Listens, and
 * connects only for a moment to read the battery level, which the brush doesn't broadcast.
 * Read-only: nothing is ever written to the brush.
 */
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

    private var protocol: Int? = null

    override suspend fun start() {}

    override fun onNotification(characteristic: UUID, value: ByteArray) {}

    override fun onAdvertisement(advertisement: Advertisement) {
        val data = advertisement.manufacturerData[OralBCodec.MANUFACTURER_ID] ?: return
        val decoded = OralBCodec.decode(data) ?: return
        protocol = OralBCodec.protocol(data)
        _state.value = decoded.copy(batteryPercent = _state.value.batteryPercent)
    }

    /** Only while idle: a connection doesn't survive the start of brushing. */
    override val wantsPoll: Boolean
        get() = protocol != null && _state.value.batteryPercent == null &&
            _state.value.activity in setOf(Activity.STANDBY, Activity.CHARGING)

    override suspend fun poll(link: BrushLink) {
        val p = protocol ?: return
        val level = OralBCodec.battery(p, link.read(OralBCodec.BATTERY)) ?: return
        _state.value = _state.value.copy(batteryPercent = level)
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
