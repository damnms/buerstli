package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Session of an experimental Laifen brush with the long frame: subscribes and listens, never
 * writes. Every status the brush sends replaces the last one.
 */
internal class LaifenListeningSession(
    private val link: BrushLink,
    private val decode: (LaifenFrame) -> BrushState?,
) : BrushSession {
    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private val ch = LaifenWaveV1Codec.CHARACTERISTIC

    override suspend fun start() {
        link.enableNotifications(ch)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != ch) return
        val frame = LaifenFrame.decode(value) ?: return
        decode(frame)?.let { _state.value = it }
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
