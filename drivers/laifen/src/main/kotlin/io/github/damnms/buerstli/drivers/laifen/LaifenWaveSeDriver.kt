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
import io.github.damnms.buerstli.domain.ModeSpec
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.ParameterSpec
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.Switch
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Laifen Wave SE (LFTB01-SE). Connects and listens, like the Wave Pro driver: nothing is ever
 * written. Whether the SE sends its status on its own is not known yet. If it doesn't, the
 * screen stays empty, and a report shows it.
 */
object LaifenWaveSeDriver : ToothbrushDriver {
    override val model = BrushModel(brand = "Laifen", name = "Wave SE (LFTB01-SE)")
    override val supportLevel = SupportLevel.EXPERIMENTAL

    // Value ranges assumed to be those of the Wave; only shown, never written.
    private fun mode(index: Int, strength: IntRange, hf: Boolean = false) = ModeSpec(
        index = index,
        parameters = listOf(
            ParameterSpec(STRENGTH, strength),
            ParameterSpec(RANGE, 1..10),
            ParameterSpec(SPEED, 1..10),
        ),
        highFrequency = hf,
        requires = if (hf) Switch.HIGH_FREQUENCY else null,
    )

    override val capabilities = Capabilities(
        modes = listOf(mode(0, 1..10), mode(1, 1..10), mode(2, 1..10), mode(3, 11..20, hf = true)),
        switches = listOf(Switch.REMINDER_30S, Switch.HIGH_FREQUENCY, Switch.FLIGHT_MODE)
            .associateWith { Access.READ_ONLY },
    )

    override fun recognizes(advertisement: Advertisement): Boolean =
        advertisement.name?.startsWith("LFTB01-SE") == true

    override fun open(link: BrushLink): BrushSession = LaifenWaveSeSession(link)
}

private class LaifenWaveSeSession(private val link: BrushLink) : BrushSession {
    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private val ch = LaifenWaveV1Codec.CHARACTERISTIC

    override suspend fun start() {
        link.enableNotifications(ch)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != ch) return
        val frame = LaifenFrame.decode(value) ?: return
        LaifenWaveSeCodec.decode(frame)?.let { _state.value = it }
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
