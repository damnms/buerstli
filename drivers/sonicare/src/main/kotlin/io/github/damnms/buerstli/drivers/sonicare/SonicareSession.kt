package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BATTERY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_MODE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_TIME
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HANDLE_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_LIMIT
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_USAGE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.INTENSITY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.ROUTINE_LENGTH
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.UUID

/**
 * Read-only. Per the protocol notes, a Sonicare drops the connection after ~6 s unless something is
 * subscribed, so subscriptions come first. Not every model has every characteristic.
 */
internal class SonicareSession(private val link: BrushLink) : BrushSession {

    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private var handleState: Int? = null
    private var brushingState: Int? = null
    private var headLimit: Int? = null
    private var headUsage: Int? = null

    override suspend fun start() {
        // The handle state is the one characteristic every classic Sonicare has; if it is missing
        // (or the brush refuses even after pairing), this isn't a brush we understand.
        withPairingRetry { link.enableNotifications(HANDLE_STATE) }
        SUBSCRIBE.forEach { optional { link.enableNotifications(it) } }

        withPairingRetry { apply(HANDLE_STATE, link.read(HANDLE_STATE)) }
        (READ - HANDLE_STATE).forEach { uuid -> optional { apply(uuid, link.read(uuid)) } }
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) = apply(characteristic, value)

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported

    private fun apply(uuid: UUID, value: ByteArray) {
        val c = SonicareCodec
        when (uuid) {
            BATTERY -> c.u8(value)?.let { v -> _state.update { it.copy(batteryPercent = v.coerceIn(0, 100)) } }
            HANDLE_STATE -> c.u8(value)?.let { handleState = it; updateActivity() }
            BRUSHING_STATE -> c.u8(value)?.let { brushingState = it; updateActivity() }
            BRUSHING_MODE -> c.u8(value)?.let { v -> _state.update { it.copy(modeName = c.mode(v)) } }
            INTENSITY -> c.u8(value)?.let { v -> _state.update { it.copy(intensity = c.intensity(v)) } }
            BRUSHING_TIME -> c.u16(value)?.let { v -> _state.update { it.copy(brushingSeconds = v) } }
            ROUTINE_LENGTH -> c.u16(value)?.let { v -> _state.update { it.copy(brushingDurationSeconds = v) } }
            HEAD_LIMIT -> c.u16(value)?.let { headLimit = it; updateWear() }
            HEAD_USAGE -> c.u16(value)?.let { headUsage = it; updateWear() }
        }
    }

    private fun updateActivity() =
        _state.update { it.copy(activity = SonicareCodec.activity(handleState, brushingState)) }

    private fun updateWear() =
        _state.update { it.copy(brushHeadWearPercent = SonicareCodec.wearPercent(headUsage, headLimit)) }

    private suspend fun optional(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // Not on this model, or not readable without pairing: show what we have.
        }
    }

    /** Some models need pairing; the first attempts fail while Android asks the user. */
    private suspend fun withPairingRetry(block: suspend () -> Unit) {
        repeat(PAIRING_ATTEMPTS - 1) {
            try {
                return block()
            } catch (_: IOException) {
                delay(PAIRING_RETRY_MS)
            }
        }
        block()
    }

    private companion object {
        val SUBSCRIBE = listOf(BRUSHING_STATE, BRUSHING_MODE, BRUSHING_TIME, ROUTINE_LENGTH, INTENSITY, BATTERY)
        val READ = listOf(HANDLE_STATE, BATTERY, BRUSHING_STATE, BRUSHING_MODE, INTENSITY, BRUSHING_TIME, ROUTINE_LENGTH, HEAD_LIMIT, HEAD_USAGE)
        const val PAIRING_ATTEMPTS = 4
        const val PAIRING_RETRY_MS = 3_000L
    }
}
