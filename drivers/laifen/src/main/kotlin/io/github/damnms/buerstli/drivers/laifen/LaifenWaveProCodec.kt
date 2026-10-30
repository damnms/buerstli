package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.Switch

/**
 * Wave Pro (LFTB02-S) payloads, as confirmed on a real brush by the Home Assistant integration
 * UrbanTechIO/Laifen. Only the fields marked confirmed there are read; the active mode is not.
 */
object LaifenWaveProCodec {
    const val STATUS = 0x0103   // 36-byte status, periodically and on every change
    const val PRESSURE = 0x020C // live pressure telemetry while brushing

    private const val MIN_STATUS = 33

    fun decode(frame: LaifenFrame): BrushState? {
        if (frame.command != STATUS || frame.payload.size < MIN_STATUS) return null
        val p = IntArray(frame.payload.size) { frame.payload[it].toInt() and 0xFF }
        fun on(i: Int) = p[i] != 0
        return BrushState(
            batteryPercent = p[2].coerceIn(0, 100),
            brushingDurationSeconds = p[5] or (p[6] shl 8),
            activity = if (p[13] == 1) Activity.BRUSHING else Activity.STANDBY, // 0 idle, 2 just stopped
            switches = mapOf(
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
