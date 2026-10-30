package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Access
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Laifen Wave Pro (LFTB02-S). Connects and listens: the brush sends its status on its own, so no
 * command is ever written. Other brushes named LFTB02-S send other payloads and show nothing,
 * which is what the "record a report" button is for.
 */
object LaifenWaveProDriver : ToothbrushDriver {
    override val model = BrushModel(brand = "Laifen", name = "Wave Pro (LFTB02)")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(
        modes = emptyList(),
        switches = listOf(
            Switch.DEEP_CLEAN, Switch.ANTI_SPLASH, Switch.POWER_RAMP_UP, Switch.SPIN_DRY,
            Switch.PRESSURE_WARNING, Switch.BRISTLE_PROTECTION, Switch.LIFT_TO_WAKE,
        ).associateWith { Access.READ_ONLY },
    )

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.name?.startsWith("LFTB02") == true

    override fun open(link: BrushLink): BrushSession = LaifenWaveProSession(link)
}

private class LaifenWaveProSession(private val link: BrushLink) : BrushSession {
    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private val ch = LaifenWaveV1Codec.CHARACTERISTIC

    override suspend fun start() {
        link.enableNotifications(ch)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != ch) return
        val frame = LaifenFrame.decode(value) ?: return
        LaifenWaveProCodec.decode(frame)?.let { status ->
            _state.update { status.copy(pressureHigh = it.pressureHigh) }
            return
        }
        LaifenWaveProCodec.overPressure(frame)?.let { high -> _state.update { it.copy(pressureHigh = high) } }
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
