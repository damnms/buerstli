package io.github.damnms.buerstli.drivers.oralb

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushCommand
import io.github.damnms.buerstli.domain.BrushLink
import io.github.damnms.buerstli.domain.BrushModel
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.BrushingRecord
import io.github.damnms.buerstli.domain.Capabilities
import io.github.damnms.buerstli.domain.CommandResult
import io.github.damnms.buerstli.domain.SupportLevel
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/**
 * Oral-B brushes that broadcast their state (iO, Genius, Smart, Pro, Triumph). Listens, and
 * connects only for a moment to read what the brush doesn't broadcast: battery, history and the
 * brush head reminder.
 * Read-only: the only thing ever written is the read request for a session (official app).
 */
object OralBPassiveDriver : ToothbrushDriver {
    override val model = BrushModel(brand = "Oral-B", name = "Bluetooth toothbrush")
    override val supportLevel = SupportLevel.EXPERIMENTAL
    override val capabilities = Capabilities(modes = emptyList(), history = SupportLevel.EXPERIMENTAL)
    override val passive = true

    override fun recognizes(advertisement: Advertisement): Boolean =
        OralBCodec.MANUFACTURER_ID in advertisement.manufacturerData

    override fun open(link: BrushLink): BrushSession = OralBSession()
}

private class OralBSession : BrushSession {
    private val _state = MutableStateFlow(BrushState.UNKNOWN)
    override val state: StateFlow<BrushState> = _state.asStateFlow()

    private var protocol: Int? = null
    private var historyRead = false
    private var brushHeadTried = false
    @Volatile private var brushHeadArrived: CompletableDeferred<Unit>? = null

    override suspend fun start() {}

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic != OralBCodec.BRUSH_HEAD) return
        val p = protocol ?: return
        OralBCodec.brushHead(p, value)?.let { left -> _state.update { it.copy(brushHeadLeft = left) } }
        brushHeadArrived?.complete(Unit)
    }

    override fun onAdvertisement(advertisement: Advertisement) {
        val data = advertisement.manufacturerData[OralBCodec.MANUFACTURER_ID] ?: return
        val decoded = OralBCodec.decode(data) ?: return
        protocol = OralBCodec.protocol(data)
        val known = _state.value
        _state.value = decoded.copy(
            batteryPercent = known.batteryPercent,
            history = known.history,
            brushHeadLeft = known.brushHeadLeft,
        )
    }

    /** Only while idle: a connection doesn't survive the start of brushing. */
    override val wantsPoll: Boolean
        get() = protocol != null && (_state.value.batteryPercent == null || !historyRead) &&
            _state.value.activity in setOf(Activity.STANDBY, Activity.CHARGING)

    /** Battery, history, brush head reminder. Throws the first failure, so the adapter may try again for what's missing. */
    override suspend fun poll(link: BrushLink) {
        val p = protocol ?: return
        var failure: IOException? = null
        if (_state.value.batteryPercent == null) {
            try {
                OralBCodec.battery(p, link.read(OralBCodec.BATTERY))?.let { level ->
                    _state.value = _state.value.copy(batteryPercent = level)
                }
            } catch (e: IOException) {
                failure = e
            }
        }
        if (!historyRead) {
            try {
                val history = readHistory(link, p)
                _state.value = _state.value.copy(history = history)
                historyRead = true
            } catch (e: IOException) {
                failure = failure ?: e
            }
        }
        if (!brushHeadTried) {
            brushHeadTried = true // optional: not every brush has it, so one try is enough
            readBrushHead(link)
        }
        failure?.let { throw it }
    }

    /** Subscribe, as the official app does, and wait a moment for the value. */
    private suspend fun readBrushHead(link: BrushLink) {
        val arrived = CompletableDeferred<Unit>()
        brushHeadArrived = arrived
        try {
            link.enableNotifications(OralBCodec.BRUSH_HEAD)
            withTimeoutOrNull(BRUSH_HEAD_WAIT_MS) { arrived.await() }
        } catch (_: IOException) {
            // No reminder on this brush.
        } finally {
            brushHeadArrived = null
        }
    }

    private companion object {
        const val BRUSH_HEAD_WAIT_MS = 3_000L
    }

    /** Newest first, until the brush has no more. A failure after the first session keeps what came. */
    private suspend fun readHistory(link: BrushLink, protocol: Int): List<BrushingRecord> {
        val history = mutableListOf<BrushingRecord>()
        for (index in 0 until OralBCodec.MAX_SESSIONS) {
            val record = try {
                link.write(OralBCodec.CONFIGURE, OralBCodec.selectSession(index))
                OralBCodec.session(protocol, link.read(OralBCodec.SESSION_DATA))
            } catch (e: IOException) {
                if (index == 0) throw e
                null
            } ?: break
            history += record
        }
        return history
    }

    override suspend fun execute(command: BrushCommand): CommandResult = CommandResult.Unsupported
}
