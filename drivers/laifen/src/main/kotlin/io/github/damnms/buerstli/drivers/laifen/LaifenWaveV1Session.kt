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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * How the LFTB01-P behaves (from the app's diagnostics log and laifen-toolbox/docs/PROTOCOL.md):
 * - The state only arrives as a notification: when asked with READ_STATE, after mode-settings
 *   writes, after button presses, and about every 10 s on its own. GATT reads return filler.
 * - A brushing-time write is only acknowledged, the new state has to be asked for.
 */
internal class LaifenWaveV1Session(
    private val link: BrushLink,
    private val capabilities: Capabilities,
) : BrushSession {

    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    /** Counts status packets, so we can wait for the next one even if it repeats the last. */
    private val statusCount = MutableStateFlow(0)

    private val ch = LaifenWaveV1Codec.CHARACTERISTIC

    override suspend fun start() {
        link.enableNotifications(ch)
        repeat(START_ATTEMPTS) {
            if (requestStatus()) return
        }
        // No answer yet: the brush will still push its state within ~10 s on its own.
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != ch) return
        val status = LaifenWaveV1Codec.decodeStatus(value) ?: return
        _state.value = status
        statusCount.update { it + 1 }
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
     * Trust, but verify: success only once the brush reports the new values itself, either pushed
     * on its own or in answer to READ_STATE. The brush may apply a change with a short delay.
     */
    private suspend fun writeAndConfirm(command: ByteArray, applied: (BrushState) -> Boolean): CommandResult =
        try {
            link.write(ch, command)
            val confirmed = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                while (!applied(_state.value)) {
                    delay(SETTLE_MS)
                    if (!applied(_state.value)) requestStatus()
                }
                true
            }
            if (confirmed == true) CommandResult.Confirmed else CommandResult.NotApplied
        } catch (e: IOException) {
            CommandResult.Failed(e.message ?: "connection lost")
        }

    /** Asks for the state and waits for the answer. Returns false if none came in time. */
    private suspend fun requestStatus(): Boolean {
        val before = statusCount.value
        link.write(ch, LaifenWaveV1Codec.READ_STATE)
        return withTimeoutOrNull(STATUS_TIMEOUT_MS) { statusCount.first { it > before } } != null
    }

    private companion object {
        const val START_ATTEMPTS = 2
        const val SETTLE_MS = 500L          // laifen-toolbox waits 500 ms after a write, too
        const val STATUS_TIMEOUT_MS = 1_500L
        const val CONFIRM_TIMEOUT_MS = 5_000L
    }
}
