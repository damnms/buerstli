package io.github.damnms.buerstli.drivers.sonicare

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.BrushingRecord
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind
import io.github.damnms.buerstli.domain.Validation
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.ACTIVE_SESSION
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.AVAILABLE_ROUTINES
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BATTERY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_MODE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.BRUSHING_TIME
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.FIRMWARE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HANDLE_STATE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_LIMIT
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.HEAD_USAGE
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.INTENSITY
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.LATEST_SESSION
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.MODEL_NUMBER
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.ROUTINE_LENGTH
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SENSOR_DATA
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.Request
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_CONTROL
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_COUNT
import io.github.damnms.buerstli.drivers.sonicare.SonicareCodec.SESSION_DATA
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Reads everything (live pressure included), and changes only mode and intensity, as the app does:
 * confirmed by reading the brush back (ADR-2), only modes the brush lists as available.
 * A Sonicare drops the connection after ~6 s unless something is subscribed, so subscriptions come first. Not every model has every characteristic.
 * The model number is read before the mode, because what a mode number means depends on it.
 * Last, the brushing history, the way the official app fetches it (docs/SONICARE.md).
 */
internal class SonicareSession(private val link: BrushLink) : BrushSession {

    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private var modelNumber: String? = null
    private var handleState: Int? = null
    private var brushingState: Int? = null
    private var routineNumber: Int? = null
    private var intensity: Int? = null
    private var available: List<Int> = emptyList()
    private var headLimit: Int? = null
    private var headUsage: Int? = null

    /** Storage notifications while the history is being fetched, null otherwise. */
    @Volatile private var inbox: Channel<Pair<UUID, ByteArray>>? = null

    override suspend fun start() {
        // The handle state is the one characteristic every classic Sonicare has; if it is missing
        // (or the brush refuses even after pairing), this isn't a brush we understand.
        withPairingRetry { link.enableNotifications(HANDLE_STATE) }
        SUBSCRIBE.forEach { optional { link.enableNotifications(it) } }

        withPairingRetry { apply(HANDLE_STATE, link.read(HANDLE_STATE)) }
        // After the handle state, so pairing is done; before the mode, which depends on it.
        optional { apply(MODEL_NUMBER, link.read(MODEL_NUMBER)) }
        (READ - HANDLE_STATE).forEach { uuid -> optional { apply(uuid, link.read(uuid)) } }
        optional { available = SonicareCodec.availableRoutines(link.read(AVAILABLE_ROUTINES)); updateMode() }
        // Live pressure, as the app switches it on; brushes without the sensor service skip this.
        optional {
            link.enableNotifications(SENSOR_DATA)
            send(Request.StreamPressure)
        }

        optional {
            val history = readHistory()
            if (history.isNotEmpty()) _state.update { it.copy(history = history) }
        }
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic in STORAGE) inbox?.trySend(characteristic to value) else apply(characteristic, value)
    }

    override suspend fun execute(command: BrushCommand): CommandResult = try {
        when (command) {
            is BrushCommand.ActivateMode -> activate(command.modeIndex)
            is BrushCommand.ChangeModeSettings -> changeIntensity(command.modeIndex, command.settings)
            else -> CommandResult.Unsupported
        }
    } catch (e: IOException) {
        CommandResult.Failed(e.message ?: "Bluetooth error")
    }

    private fun activeRoutine(): Int? = SonicareCodec.routineId(_state.value.modeName)

    private suspend fun activate(routineId: Int): CommandResult {
        if (routineId !in available) return CommandResult.UnknownMode(routineId)
        if (activeRoutine() == routineId) return CommandResult.Confirmed
        send(Request.SelectRoutine(routineId))
        return confirm(BRUSHING_MODE) { activeRoutine() == routineId }
    }

    private suspend fun changeIntensity(routineId: Int, settings: ModeSettings): CommandResult {
        if (routineId !in available) return CommandResult.UnknownMode(routineId)
        val spec = SonicareClassicDriver.capabilities.mode(routineId) ?: return CommandResult.UnknownMode(routineId)
        spec.validate(settings).let { if (it != Validation.Valid) return CommandResult.Invalid(it) }
        val level = settings[ParameterKind.INTENSITY]!!
        // "Save and use": the intensity belongs to the active mode, so switch first.
        activate(routineId).let { if (it != CommandResult.Confirmed) return it }
        if (intensity == level) return CommandResult.Confirmed
        send(Request.SetIntensity(level))
        return confirm(INTENSITY) { intensity == level }
    }

    /** Reads [uuid] back until [applied] holds; the brush may take a moment. */
    private suspend fun confirm(uuid: UUID, applied: () -> Boolean): CommandResult {
        repeat(CONFIRM_ATTEMPTS) {
            if (applied()) return CommandResult.Confirmed
            delay(CONFIRM_INTERVAL_MS)
            apply(uuid, link.read(uuid))
        }
        return if (applied()) CommandResult.Confirmed else CommandResult.NotApplied
    }

    private fun apply(uuid: UUID, value: ByteArray) {
        val c = SonicareCodec
        when (uuid) {
            MODEL_NUMBER -> c.text(value)?.let { m -> modelNumber = m; _state.update { it.copy(modelName = m) }; updateMode() }
            FIRMWARE -> c.text(value)?.let { v -> _state.update { it.copy(firmwareVersion = v) } }
            BATTERY -> c.u8(value)?.let { v -> _state.update { it.copy(batteryPercent = v.coerceIn(0, 100)) } }
            HANDLE_STATE -> c.u8(value)?.let { handleState = it; updateActivity() }
            SENSOR_DATA -> c.pressureHigh(value)?.let { v -> _state.update { it.copy(pressureHigh = v) } }
            BRUSHING_STATE -> c.u8(value)?.let { brushingState = it; updateActivity() }
            BRUSHING_MODE -> c.u8(value)?.let { routineNumber = it; updateMode() }
            INTENSITY -> c.u8(value)?.let { v -> intensity = v; _state.update { it.copy(intensity = c.intensity(v)) }; updateModes() }
            BRUSHING_TIME -> c.u16(value)?.let { v -> _state.update { it.copy(brushingSeconds = v) } }
            ROUTINE_LENGTH -> c.u16(value)?.let { v -> _state.update { it.copy(brushingDurationSeconds = v) } }
            HEAD_LIMIT -> c.u16(value)?.let { headLimit = it; updateWear() }
            HEAD_USAGE -> c.u16(value)?.let { headUsage = it; updateWear() }
        }
    }

    private fun updateMode() {
        _state.update { it.copy(modeName = routineNumber?.let { n -> SonicareCodec.mode(n, modelNumber) }) }
        updateModes()
    }

    /** The modes the brush offers; the active one carries the intensity. */
    private fun updateModes() {
        val active = activeRoutine()
        val level = intensity?.takeIf { it in 0..2 }
        _state.update { s ->
            s.copy(
                activeModeIndex = active,
                modes = available.associateWith { id ->
                    if (id == active && level != null) ModeSettings.of(ParameterKind.INTENSITY to level) else ModeSettings(emptyMap())
                },
            )
        }
    }

    private fun updateActivity() = _state.update {
        val activity = SonicareCodec.activity(handleState, brushingState)
        // Pressure only means something while brushing; the brush stops sending it afterwards.
        val brushing = activity == Activity.BRUSHING || activity == Activity.PAUSED
        it.copy(activity = activity, pressureHigh = if (brushing) it.pressureHigh else null)
    }

    private fun updateWear() =
        _state.update { it.copy(brushHeadWearPercent = SonicareCodec.wearPercent(headUsage, headLimit)) }

    /**
     * Sessions the brush stores, newest first. Throws IOException if the brush has no storage
     * service; stops after [HISTORY_BUDGET_MS] with what arrived by then.
     */
    private suspend fun readHistory(): List<BrushingRecord> {
        STORAGE.forEach { link.enableNotifications(it) }
        send(Request.ReadSessions)
        val latest = SonicareCodec.u16(link.read(LATEST_SESSION)) ?: return emptyList()
        val count = link.read(SESSION_COUNT).let { SonicareCodec.u16(it) ?: SonicareCodec.u8(it) } ?: 0
        if (count <= 0) return emptyList()
        val first = (latest - (minOf(count, MAX_SESSIONS) - 1)).coerceAtLeast(0)

        val records = mutableListOf<BrushingRecord>()
        val box = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
        inbox = box
        try {
            send(Request.StartAt(first))
            send(Request.SendAll)
            val complete = withTimeoutOrNull(HISTORY_BUDGET_MS) {
                var current = first
                var firstPacket: ByteArray? = null
                while (true) {
                    val (uuid, value) = box.receive()
                    when (uuid) {
                        ACTIVE_SESSION -> SonicareCodec.u16(value)?.let { current = it; firstPacket = null }
                        SESSION_DATA -> if (firstPacket == null) firstPacket = value
                        SESSION_CONTROL -> if (SonicareCodec.u8(value) == 0) {
                            val record = firstPacket?.let { SonicareCodec.session(it, modelNumber) }
                            record?.let { records += it }
                            firstPacket = null
                            if (current == latest || record?.number == latest) break
                        }
                    }
                }
                true
            }
            if (complete == null) optional { send(Request.Cancel) }
        } finally {
            inbox = null
        }
        return records.distinctBy { it.number }.sortedByDescending { it.number }
    }

    private suspend fun send(request: Request) = link.write(request.characteristic, request.bytes)

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
        val READ = listOf(
            HANDLE_STATE, FIRMWARE, BATTERY, BRUSHING_STATE, BRUSHING_MODE, INTENSITY, BRUSHING_TIME,
            ROUTINE_LENGTH, HEAD_LIMIT, HEAD_USAGE,
        )
        val STORAGE = setOf(ACTIVE_SESSION, SESSION_DATA, SESSION_CONTROL)
        const val CONFIRM_ATTEMPTS = 6
        const val CONFIRM_INTERVAL_MS = 300L
        const val MAX_SESSIONS = 30
        const val HISTORY_BUDGET_MS = 8_000L
        const val PAIRING_ATTEMPTS = 4
        const val PAIRING_RETRY_MS = 3_000L
    }
}
