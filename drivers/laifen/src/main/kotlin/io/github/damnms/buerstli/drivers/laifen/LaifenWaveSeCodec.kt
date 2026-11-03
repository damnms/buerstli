package io.github.damnms.buerstli.drivers.laifen

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.ModeSettings
import io.github.damnms.buerstli.domain.ParameterKind.RANGE
import io.github.damnms.buerstli.domain.ParameterKind.SPEED
import io.github.damnms.buerstli.domain.ParameterKind.STRENGTH
import io.github.damnms.buerstli.domain.Switch

/**
 * Wave SE (LFTB01-SE) status, carried in the long [LaifenFrame] as command 0x0103. Pure
 * translation, no I/O.
 *
 * Layout found by analysing the official app for interoperability (only the protocol, no code
 * taken over). The app reads it for every newer brush, and for the SE it is the only reader.
 * Not confirmed on a real SE yet.
 *
 *   payload  0      active mode
 *            1-12   4 x (strength, range, speed): modes 1-3, high frequency
 *            13     flight mode
 *            14     battery %
 *            15     30-second reminder
 *            16-23  4 x brushing duration in seconds (16 bit big-endian), one per mode
 *            24     high frequency
 *            25     motor running
 *            26-28  emergency power, charging state, constant power (not read yet)
 *
 * Up to byte 15 this is the order of the Wave V1 status, so the value names follow
 * [LaifenWaveV1Codec]. A boolean is true only for exactly 01.
 */
object LaifenWaveSeCodec {
    const val STATUS = 0x0103
    const val MODE_COUNT = 4
    private const val MIN_STATUS = 29

    fun decode(frame: LaifenFrame): BrushState? {
        if (frame.command != STATUS || frame.payload.size < MIN_STATUS) return null
        val p = IntArray(frame.payload.size) { frame.payload[it].toInt() and 0xFF }
        fun on(i: Int) = p[i] == 1

        val active = p[0].takeIf { it in 0 until MODE_COUNT }
        val modes = (0 until MODE_COUNT).associateWith { m ->
            val b = 1 + 3 * m
            ModeSettings.of(STRENGTH to p[b], RANGE to p[b + 1], SPEED to p[b + 2])
        }
        val durations = (0 until MODE_COUNT).map { m -> (p[16 + 2 * m] shl 8) or p[17 + 2 * m] }

        return BrushState(
            batteryPercent = p[14].coerceIn(0, 100),
            activeModeIndex = active,
            modes = modes,
            brushingDurationSeconds = active?.let { durations[it] },
            activity = if (on(25)) Activity.BRUSHING else Activity.STANDBY,
            switches = mapOf(
                Switch.FLIGHT_MODE to on(13),
                Switch.REMINDER_30S to on(15),
                Switch.HIGH_FREQUENCY to on(24),
            ),
        )
    }
}
