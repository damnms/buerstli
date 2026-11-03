package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Switch

/**
 * Status of the kids' TC51 (LFTB01-C), carried in the long [LaifenFrame] as command 0x0103.
 * Pure translation, no I/O.
 *
 * Layout found by analysing the official app for interoperability (only the protocol, no code
 * taken over). Not confirmed on a real TC51 yet. It is a relative of the Wave Pro status
 * ([LaifenWaveProCodec]): battery, durations, activity and the 24-26 switches sit in the same
 * places.
 *
 *   payload  0      active mode
 *            1      flight mode
 *            2      battery %
 *            4-11   4 x brushing duration in seconds (16 bit big-endian), one per mode
 *            13     0 idle, 1 brushing, 2 just stopped (as on the Wave Pro)
 *            15     child lock
 *            24     power ramp-up ("fade in")
 *            25     spin dry
 *            26     pressure warning
 *            36     30-second reminder
 *            41     high frequency
 *   Not read yet: 3 low-frequency value, 12 low frequency, 14 charging, 16-17, 18-19 brush-head
 *   uses left, 20-23 last times, 27-35, 37-40, 42 age. The app reads everything from 29 on
 *   only if it's there, so those fields are optional here too.
 *
 * A boolean is true only for exactly 01.
 */
object LaifenTc51Codec {
    const val STATUS = 0x0103
    const val MODE_COUNT = 4
    private const val MIN_STATUS = 29

    fun decode(frame: LaifenFrame): BrushState? {
        if (frame.command != STATUS || frame.payload.size < MIN_STATUS) return null
        val p = IntArray(frame.payload.size) { frame.payload[it].toInt() and 0xFF }
        fun on(i: Int) = p[i] == 1
        fun optional(i: Int) = if (i < p.size) on(i) else null

        val active = p[0].takeIf { it in 0 until MODE_COUNT }
        val switches = buildMap {
            put(Switch.FLIGHT_MODE, on(1))
            put(Switch.CHILD_LOCK, on(15))
            put(Switch.POWER_RAMP_UP, on(24))
            put(Switch.SPIN_DRY, on(25))
            put(Switch.PRESSURE_WARNING, on(26))
            optional(36)?.let { put(Switch.REMINDER_30S, it) }
            optional(41)?.let { put(Switch.HIGH_FREQUENCY, it) }
        }
        return BrushState(
            batteryPercent = p[2].coerceIn(0, 100),
            brushingDurationSeconds = active?.let { (p[4 + 2 * it] shl 8) or p[5 + 2 * it] },
            activity = if (p[13] == 1) Activity.BRUSHING else Activity.STANDBY,
            switches = switches,
        )
    }
}
