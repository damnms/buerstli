package io.github.damnms.buerstli.drivers.oralb

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.ModeName

/**
 * Oral-B advertisement data (manufacturer id 0x00DC), as decoded by oralb-ble (MIT), the library
 * behind Home Assistant's Oral-B integration, and verified there against many real brushes:
 *
 *   0 protocol version · 1 model id · 3 state · 4 pressure/button flags · 5-6 time (min, s)
 *   7 mode · 8 sector (low 3 bits) · 9 sector timer · 10 number of sectors (only in 11-byte form)
 *
 * The battery level is not in the advertisements.
 */
object OralBCodec {
    const val MANUFACTURER_ID = 0x00DC

    private enum class Family { SMART, IO }

    private class Model(val name: String, val family: Family)

    private val models: Map<Int, Model> = buildMap {
        fun line(ids: IntRange, name: String, family: Family = Family.SMART) = ids.forEach { put(it, Model(name, family)) }
        line(0..2, "Triumph D36")
        line(32..34, "Genius Series D701")
        line(39..41, "Smart Series D700")
        line(48..50, "iO Series", Family.IO)
        put(52, Model("iO Series 4", Family.IO))
        put(53, Model("iO Series 5", Family.IO))
        put(54, Model("iO Series", Family.IO))
        line(64..70, "Smart Series D21")
        line(80..87, "Pro Series D601")
        line(112..114, "Genius X D706")
        line(117..119, "Genius X D706")
    }

    private val smartModes = mapOf(
        1 to ModeName.DAILY_CLEAN, 2 to ModeName.SENSITIVE, 3 to ModeName.MASSAGE, 4 to ModeName.WHITEN,
        5 to ModeName.DEEP_CLEAN, 6 to ModeName.TONGUE_CARE, 7 to ModeName.TURBO,
    )

    private val ioModes = mapOf(
        0 to ModeName.DAILY_CLEAN, 1 to ModeName.SENSITIVE, 2 to ModeName.GUM_CARE, 3 to ModeName.WHITEN,
        4 to ModeName.INTENSE, 5 to ModeName.SUPER_SENSITIVE, 6 to ModeName.TONGUE_CARE, 11 to ModeName.SMART_ADAPT,
    )

    private const val RUNNING = 3

    fun decode(data: ByteArray): BrushState? {
        if (data.size < 9) return null
        val u = IntArray(data.size) { data[it].toInt() and 0xFF }
        val model = models[u[1]]
        val state = u[3]
        val sectorCount = if (u.size >= 11) u[10].takeIf { it > 0 } else null
        return BrushState(
            modelName = model?.name,
            activity = activity(state),
            pressureHigh = pressureHigh(u[4]),
            brushingSeconds = u[5] * 60 + u[6],
            modeName = (if (model?.family == Family.IO) ioModes else smartModes)[u[7]],
            // The brush keeps reporting the last sector when idle; it only means something while brushing.
            sector = if (state == RUNNING) sector(u[8], sectorCount) else null,
            sectorCount = sectorCount,
        )
    }

    private fun activity(state: Int): Activity? = when (state) {
        3 -> Activity.BRUSHING
        4 -> Activity.CHARGING
        9, 115 -> Activity.OFF // off, sleeping
        1, 2, 5, 6, 8, 10 -> Activity.STANDBY // initializing, idle, setup, menus, statistics
        else -> null
    }

    /** Bit field: power button (bit 3) and mode button (bit 2) win over high pressure (bit 7). */
    private fun pressureHigh(b: Int): Boolean? = when {
        b and 0x08 != 0 || b and 0x04 != 0 -> null
        else -> b and 0x80 != 0
    }

    /** Low 3 bits: 0 none, 1-6 the sector, 7 "last sector" = the sector count (or 4). */
    private fun sector(b: Int, count: Int?): Int? = when (val q = b and 0x07) {
        0 -> null
        7 -> (count ?: 0).and(0x07).takeIf { it > 0 } ?: 4
        else -> q
    }
}
