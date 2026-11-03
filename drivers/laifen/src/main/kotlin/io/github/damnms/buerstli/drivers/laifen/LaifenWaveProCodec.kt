package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Switch

/**
 * Wave Pro (LFTB02-S) payloads. The Pro features are named as confirmed on a real brush by the
 * Home Assistant integration UrbanTechIO/Laifen. The rest of the status layout was found by
 * analysing the official app for interoperability (only the protocol, no code taken over):
 *
 *   payload  0      active mode
 *            1      flight mode
 *            2      battery %
 *            3      30-second reminder
 *            4-11   4 x brushing duration in seconds (16 bit big-endian), one per mode
 *            12     high frequency
 *            13     0 idle, 1 brushing, 2 just stopped
 *            15, 16, 24, 25, 26, 30, 32   Pro features, see below
 *
 * The app names 16 "pressure sensor on" and 30 "sleep protection"; the labels here follow what
 * the Home Assistant integration saw change when switching the features in the vendor app.
 * The modes themselves are not read: their values aren't part of this status.
 */
object LaifenWaveProCodec {
    const val STATUS = 0x0103   // 36-byte status, periodically and on every change
    const val PRESSURE = 0x020C // live pressure telemetry while brushing
    const val MODE_COUNT = 4

    private const val MIN_STATUS = 33

    fun decode(frame: LaifenFrame): BrushState? {
        if (frame.command != STATUS || frame.payload.size < MIN_STATUS) return null
        val p = IntArray(frame.payload.size) { frame.payload[it].toInt() and 0xFF }
        fun on(i: Int) = p[i] != 0
        // Each mode has its own duration; the active mode's is the one that counts.
        val active = p[0].takeIf { it in 0 until MODE_COUNT }
        return BrushState(
            batteryPercent = p[2].coerceIn(0, 100),
            brushingDurationSeconds = active?.let { (p[4 + 2 * it] shl 8) or p[5 + 2 * it] },
            activity = if (p[13] == 1) Activity.BRUSHING else Activity.STANDBY, // 0 idle, 2 just stopped
            switches = mapOf(
                Switch.FLIGHT_MODE to (p[1] == 1),
                Switch.REMINDER_30S to (p[3] == 1),
                Switch.HIGH_FREQUENCY to (p[12] == 1),
                Switch.DEEP_CLEAN to on(15),
                Switch.ANTI_SPLASH to on(16),
                Switch.POWER_RAMP_UP to on(24),
                Switch.SPIN_DRY to on(25),
                Switch.PRESSURE_WARNING to on(26),
                Switch.BRISTLE_PROTECTION to on(30),
                Switch.LIFT_TO_WAKE to on(32),
            ),
        )
    }

    /** True while the brush is pressed too hard, from the telemetry frame. */
    fun overPressure(frame: LaifenFrame): Boolean? =
        if (frame.command == PRESSURE && frame.payload.size >= 3) frame.payload[2].toInt() != 0 else null
}
