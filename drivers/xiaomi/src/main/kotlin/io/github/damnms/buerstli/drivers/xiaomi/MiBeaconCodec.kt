package io.github.damnms.buerstli.drivers.xiaomi

import io.github.damnms.buerstli.domain.Activity
import io.github.damnms.buerstli.domain.BrushState
import java.util.UUID

/** One data object of a MiBeacon: id, then its bytes. */
internal class MiObject(val id: Int, val data: ByteArray)

/** A MiBeacon frame, as far as Bürstli reads it. Objects only for frames that are not encrypted. */
internal class MiBeacon(
    val productId: Int,
    val frameCounter: Int,
    val encrypted: Boolean,
    val objects: List<MiObject>,
)

/**
 * Xiaomi's MiBeacon: service data under 0xFE95. Layout and object ids as documented by the
 * Home Assistant parsers xiaomi-ble and ble_monitor, see docs/XIAOMI.md.
 */
internal object MiBeaconCodec {
    val SERVICE: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

    /** Product id of the Mi Smart Electric Toothbrush T500 (soocare.toothbrush.m1s). */
    const val T500 = 0x0489

    // Frame control bits
    private const val ENCRYPTED = 1 shl 3
    private const val MAC_INCLUDED = 1 shl 4
    private const val CAPABILITY_INCLUDED = 1 shl 5
    private const val OBJECT_INCLUDED = 1 shl 6
    private const val MESH = 1 shl 7

    /** In the capability byte: one more byte (I/O capability) follows. */
    private const val CAPABILITY_IO = 0x20

    // Object ids
    const val TOOTHBRUSH = 0x0010
    const val BATTERY = 0x100A
    const val CONSUMABLE = 0x1013
    const val BRUSHING = 0x3003

    fun productId(data: ByteArray): Int? = if (data.size < 4) null else u16(data, 2)

    /** Null for frames Bürstli doesn't read: too short, mesh, or version 0 and 1. */
    fun parse(data: ByteArray): MiBeacon? {
        if (data.size < 5) return null
        val control = u16(data, 0)
        if (control and MESH != 0 || control ushr 12 < 2) return null
        var i = 5 // frame control, product id, frame counter
        if (control and MAC_INCLUDED != 0) i += 6
        if (control and CAPABILITY_INCLUDED != 0) {
            if (data.size <= i) return null
            val capability = data[i].toInt() and 0xFF
            i += 1
            if (capability and CAPABILITY_IO != 0) i += 1
        }
        if (data.size < i) return null
        val encrypted = control and ENCRYPTED != 0
        val objects = if (encrypted || control and OBJECT_INCLUDED == 0) emptyList() else objects(data, i)
        return MiBeacon(u16(data, 2), data[4].toInt() and 0xFF, encrypted, objects)
    }

    /** id (2 bytes, little-endian), length (1 byte), data. Stops at the first object that doesn't fit. */
    private fun objects(data: ByteArray, from: Int): List<MiObject> {
        val objects = mutableListOf<MiObject>()
        var i = from
        while (i + 3 <= data.size) {
            val length = data[i + 2].toInt() and 0xFF
            if (i + 3 + length > data.size) break
            objects += MiObject(u16(data, i), data.copyOfRange(i + 3, i + 3 + length))
            i += 3 + length
        }
        return objects
    }

    /** What one object tells about the brush. Unknown objects and values leave the state as it is. */
    fun apply(state: BrushState, obj: MiObject): BrushState {
        val d = obj.data
        if (d.isEmpty()) return state
        val first = d[0].toInt() and 0xFF
        return when (obj.id) {
            // 0 = brushing started (the second byte is a counter of unknown meaning),
            // 1 = brushing ended (the second byte is the score).
            TOOTHBRUSH -> when (first) {
                0 -> state.copy(activity = Activity.BRUSHING)
                1 -> state.copy(
                    activity = Activity.OFF,
                    brushingScore = if (d.size > 1) d[1].toInt() and 0xFF else state.brushingScore,
                )
                else -> state
            }
            // 0 = start, 1 = end; then a timestamp (4 bytes, not used); score in byte 5.
            BRUSHING -> when (first) {
                0 -> state.copy(activity = Activity.BRUSHING)
                1 -> state.copy(
                    activity = Activity.OFF,
                    brushingScore = if (d.size == 6) d[5].toInt() and 0xFF else state.brushingScore,
                )
                else -> state
            }
            BATTERY -> if (first <= 100) state.copy(batteryPercent = first) else state
            // Xiaomi's object: what is left of the consumable in percent; Bürstli shows how worn it is.
            CONSUMABLE -> if (first <= 100) state.copy(brushHeadWearPercent = 100 - first) else state
            else -> state
        }
    }

    private fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
}
