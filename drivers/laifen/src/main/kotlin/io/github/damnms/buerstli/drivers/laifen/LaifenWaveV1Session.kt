package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.Validation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.UUID

internal class LaifenWaveV1Session(
    private val link: BrushLink,
    private val capabilities: Capabilities,
) : BrushSession {

    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private val ch = LaifenWaveV1Codec.CHARACTERISTIC

    override suspend fun start() {
        link.enableNotifications(ch)
        refresh()
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != ch) return
        LaifenWaveV1Codec.decodeStatus(value)?.let { _state.value = it }
    }

    override suspend fun execute(command: BrushCommand): CommandResult = when (command) {
        is BrushCommand.ChangeModeSettings -> changeModeSettings(command)
    }

    private suspend fun changeModeSettings(cmd: BrushCommand.ChangeModeSettings): CommandResult {
        val spec = capabilities.mode(cmd.modeIndex) ?: return CommandResult.UnknownMode(cmd.modeIndex)
        val validation = spec.validate(cmd.settings)
        if (validation != Validation.Valid) return CommandResult.Invalid(validation)

        return try {
            link.write(ch, LaifenWaveV1Codec.encodeModeSettings(cmd.modeIndex, cmd.settings))
            // Trust, but verify: only report success if the brush says so itself.
            val after = refresh()
            if (after?.modes?.get(cmd.modeIndex) == cmd.settings) CommandResult.Confirmed
            else CommandResult.NotApplied
        } catch (e: IOException) {
            CommandResult.Failed(e.message ?: "connection lost")
        }
    }

    /** Reads the status. A read right after a write may return an ack instead, so try a few times. */
    private suspend fun refresh(): BrushState? {
        repeat(READ_ATTEMPTS) {
            LaifenWaveV1Codec.decodeStatus(link.read(ch))?.let {
                _state.value = it
                return it
            }
        }
        return null
    }

    private companion object {
        const val READ_ATTEMPTS = 3
    }
}
