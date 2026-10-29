package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.Validation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
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
        is BrushCommand.ChangeBrushingDuration -> changeBrushingDuration(command)
    }

    private suspend fun changeModeSettings(cmd: BrushCommand.ChangeModeSettings): CommandResult {
        val spec = capabilities.mode(cmd.modeIndex) ?: return CommandResult.UnknownMode(cmd.modeIndex)
        val validation = spec.validate(cmd.settings)
        if (validation != Validation.Valid) return CommandResult.Invalid(validation)

        return writeAndConfirm(LaifenWaveV1Codec.encodeModeSettings(cmd.modeIndex, cmd.settings)) {
            it.modes[cmd.modeIndex] == cmd.settings
        }
    }

    private suspend fun changeBrushingDuration(cmd: BrushCommand.ChangeBrushingDuration): CommandResult {
        val spec = capabilities.brushingDuration ?: return CommandResult.Unsupported
        val validation = spec.validate(cmd.seconds)
        if (validation != Validation.Valid) return CommandResult.Invalid(validation)

        return writeAndConfirm(LaifenWaveV1Codec.encodeBrushingDuration(cmd.seconds)) {
            it.brushingDurationSeconds == cmd.seconds
        }
    }

    /**
     * Trust, but verify: only report success once the brush reports the new values itself.
     * The real brush applies a write with a short delay, so the confirmation may come from
     * a later read or from a notification, whichever is first.
     */
    private suspend fun writeAndConfirm(command: ByteArray, applied: (BrushState) -> Boolean): CommandResult =
        try {
            link.write(ch, command)
            val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                while (!applied(_state.value)) {
                    delay(POLL_INTERVAL_MS)
                    if (!applied(_state.value)) refresh()
                }
                true
            }
            if (confirmed == true) CommandResult.Confirmed else CommandResult.NotApplied
        } catch (e: IOException) {
            CommandResult.Failed(e.message ?: "connection lost")
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
        const val POLL_INTERVAL_MS = 250L
        const val CONFIRM_TIMEOUT_MS = 3_000L
    }
}
