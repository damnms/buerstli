package io.github.damnms.buerstli.drivers.oclean

import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.BATTERY
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.FIRMWARE
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.HARDWARE
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.MODEL_NUMBER
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.REPLY
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.SESSIONS
import io.github.damnms.buerstli.drivers.oclean.OcleanCodec.SESSION_REQUEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.ZoneId
import java.util.UUID

/**
 * Only reads. Every Oclean: the model from Device Information, firmware and battery, which are
 * standard characteristics. Only brushes of the type-1 protocol ([OcleanCodec.TYPE_1]) also get the
 * two read commands, `0303` (status, for the battery if 2A19 fails) and `0307` (stored sessions),
 * both to [SESSION_REQUEST] as ha-oclean-integration found the firmware wants them. Bürstli never sends `0202`, which most likely deletes the sessions,
 * and doesn't set the brush's clock.
 */
internal class OcleanSession(
    private val link: BrushLink,
    /** The zone the brush's clock is read in; a parameter so tests can fix it. */
    private val zone: ZoneId = ZoneId.systemDefault(),
) : BrushSession {

    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private val stream = OcleanCodec.SessionStream(zone)

    /** Session notifications while the sessions are being fetched, null otherwise. */
    @Volatile private var inbox: Channel<ByteArray>? = null

    override suspend fun start() {
        var modelNumber: String? = null
        var code: String? = null
        optional { modelNumber = OcleanCodec.text(link.read(MODEL_NUMBER)) }
        optional { code = OcleanCodec.modelCode(link.read(HARDWARE)) }
        _state.update { it.copy(modelName = OcleanCodec.displayName(modelNumber, code)) }
        optional { OcleanCodec.text(link.read(FIRMWARE))?.let { v -> _state.update { it.copy(firmwareVersion = v) } } }
        optional { link.enableNotifications(BATTERY) }
        optional { apply(BATTERY, link.read(BATTERY)) }

        // Other Oclean protocols differ; no command goes to a brush nobody has checked.
        if (!OcleanCodec.isType1(modelNumber, code)) return

        optional {
            link.enableNotifications(REPLY)
            if (_state.value.batteryPercent == null) link.write(SESSION_REQUEST, OcleanCodec.QUERY_STATUS)
        }
        optional {
            val sessions = readSessions()
            if (sessions != null) show(sessions)
        }
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) = apply(characteristic, value)

    /** Nothing can be changed on an Oclean with Bürstli (ADR-6). */
    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported

    private fun apply(uuid: UUID, value: ByteArray) {
        when (uuid) {
            BATTERY -> value.firstOrNull()?.let { b ->
                val v = b.toInt() and 0xFF
                if (v in 0..100) _state.update { it.copy(batteryPercent = v) }
            }
            REPLY -> OcleanCodec.statusBattery(value)?.let { v -> _state.update { it.copy(batteryPercent = v) } }
            SESSIONS -> {
                val box = inbox
                if (box != null) box.trySend(value) else stream.accept(value)?.let(::show)
            }
        }
    }

    /**
     * Asks for the stored sessions. Null if no answer comes within [SESSIONS_BUDGET_MS]; once it
     * comes, a pause of [QUIET_MS] ends the transfer with what arrived.
     */
    private suspend fun readSessions(): List<OcleanCodec.Session>? {
        link.enableNotifications(SESSIONS)
        val box = Channel<ByteArray>(Channel.UNLIMITED)
        inbox = box
        try {
            link.write(SESSION_REQUEST, OcleanCodec.QUERY_SESSIONS)
            val first = withTimeoutOrNull(SESSIONS_BUDGET_MS) { box.receive() } ?: return stream.flush()
            var done = stream.accept(first)
            while (done == null) {
                val next = withTimeoutOrNull(QUIET_MS) { box.receive() }
                done = if (next == null) stream.flush() ?: emptyList() else stream.accept(next)
            }
            return done
        } finally {
            inbox = null
        }
    }

    /**
     * Newest first. Oclean doesn't number its sessions, so Bürstli counts them, the oldest as 1.
     * Asking for them deletes nothing (the app deletes them with a separate command), so the same
     * sessions come again next time.
     */
    private fun show(sessions: List<OcleanCodec.Session>) {
        val newestFirst = sessions
            .distinctBy { it.record.startedAt }
            .sortedByDescending { it.record.startedAt }
        val history = newestFirst.mapIndexed { i, s -> s.record.copy(number = newestFirst.size - i) }
        val score = newestFirst.firstOrNull()?.score
        _state.update { it.copy(history = history, brushingScore = score ?: it.brushingScore) }
    }

    private suspend fun optional(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            // Not on this model: show what we have.
        }
    }

    private companion object {
        const val SESSIONS_BUDGET_MS = 8_000L
        const val QUIET_MS = 1_500L
    }
}
