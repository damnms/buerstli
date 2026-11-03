package io.github.damnms.buerstli.drivers.oralb

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.ModeName

/**
 * Oral-B advertisement data (manufacturer id 0x00DC):
 *
 *   0 protocol version · 1 model id · 3 state · 4 pressure/button flags · 5-6 time (min, s)
 *   7 mode · 8 sector (low 3 bits) · 9 sector timer, from protocol 8 the iO series ·
 *   10 number of sectors (only in 11-byte form)
 *
 * The layout and most values come from oralb-ble (MIT), the library behind Home Assistant's
 * Oral-B integration, verified there against many real brushes. Where the official Oral-B app
 * knows more or says otherwise, the app wins (ADR-6); docs/ORAL-B.md lists which value comes
 * from where. The battery level is not in the advertisements.
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
        put(165, Model("iO Series 5", Family.IO)) // official app: "HCB GALAXY (IO5)"
        put(166, Model("iO Series", Family.IO)) // official app: "HCB EPLATFORM"
    }

    /** From protocol 8 on, byte 9 names the iO series (official app). */
    private const val SERIES_PROTOCOL = 8
    private val ioSeries = mapOf(1 to 9, 2 to 8, 3 to 7, 4 to 6, 5 to 5, 6 to 4, 16 to 10)

    /** The protocol version decides how modes are numbered (official app), not the model. */
    private const val IO_MODES_PROTOCOL = 6

    // 0 = off
    private val olderModes = mapOf(
        1 to ModeName.DAILY_CLEAN, 2 to ModeName.SENSITIVE, 3 to ModeName.MASSAGE, 4 to ModeName.WHITEN,
        5 to ModeName.DEEP_CLEAN, 6 to ModeName.TONGUE_CARE, 7 to ModeName.PRO_CLEAN,
    )

    // 7 = off, 8 = settings
    private val ioModes = mapOf(
        0 to ModeName.DAILY_CLEAN, 1 to ModeName.SENSITIVE, 2 to ModeName.GUM_CARE, 3 to ModeName.WHITEN,
        4 to ModeName.INTENSE, 5 to ModeName.SUPER_SENSITIVE, 6 to ModeName.TONGUE_CARE, 11 to ModeName.SMART_ADAPT,
        12 to ModeName.GENTLE_WHITE,
    )

    private const val RUNNING = 3

    fun decode(data: ByteArray): BrushState? {
        if (data.size < 9) return null
        val u = IntArray(data.size) { data[it].toInt() and 0xFF }
        val protocol = u[0]
        val model = models[u[1]]
        val state = u[3]
        val sectorCount = if (u.size >= 11) u[10].takeIf { it > 0 } else null
        return BrushState(
            modelName = modelName(protocol, model, u),
            activity = activity(state),
            pressureHigh = pressureHigh(u[4]),
            brushingSeconds = u[5] * 60 + u[6],
            modeName = (if (protocol >= IO_MODES_PROTOCOL) ioModes else olderModes)[u[7]],
            // The brush keeps reporting the last sector when idle; it only means something while brushing.
            sector = if (state == RUNNING) sector(u[8], sectorCount) else null,
            sectorCount = sectorCount,
        )
    }

    private fun modelName(protocol: Int, model: Model?, u: IntArray): String? {
        val mayNameSeries = model == null || model.family == Family.IO
        val series = if (protocol >= SERIES_PROTOCOL && u.size >= 10 && mayNameSeries) ioSeries[u[9]] else null
        return series?.let { "iO Series $it" } ?: model?.name
    }

    private fun activity(state: Int): Activity? = when (state) {
        3 -> Activity.BRUSHING
        4 -> Activity.CHARGING
        9 -> Activity.PAUSED // official app; oralb-ble calls it "off"
        115, 116 -> Activity.OFF // sleeping, transport mode
        1, 2, 5, 6, 8, 10 -> Activity.STANDBY // initializing, idle, setup, menus, about to start, statistics
        else -> null // includes 7: on the charger, but charging not allowed
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
