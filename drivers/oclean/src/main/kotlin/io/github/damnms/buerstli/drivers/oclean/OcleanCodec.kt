package io.github.damnms.buerstli.drivers.oclean

import io.github.damnms.buerstli.domain.BrushingRecord
import java.io.ByteArrayOutputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Oclean, the protocol of the X series ("type 1"). Sources: the official app Oclean Care+ 4.0.8,
 * studied, not copied, and ha-oclean-integration (MIT), whose formats were checked on real brushes;
 * every value and where it comes from is in docs/OCLEAN.md.
 */
object OcleanCodec {
    private fun sig(short: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val SERVICE: UUID = UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc18")
    /** Commands (the app's OCLEAN_WRITE_INFO). */
    val WRITE: UUID = UUID.fromString("9d84b9a3-000c-49d8-9183-855b673fbb85")
    /** Replies to commands on [WRITE] (OCLEAN_READ_INFO), notify. */
    val REPLY: UUID = UUID.fromString("5f78df94-798c-46f5-990a-855b673fbb86")
    /**
     * Read commands of the X series go here (OCLEAN_SEND_BRUSH_CMD); write only. The app 4.0.8 sends
     * only `0307` here and `0303` to [WRITE], but ha-oclean-integration found that the X series'
     * firmware wants all its queries here.
     */
    val SESSION_REQUEST: UUID = UUID.fromString("5f78df94-798c-46f5-990a-855b673fbb89")
    /** Brushing sessions (OCLEAN_RECEIVE_BRUSH), notify. */
    val SESSIONS: UUID = UUID.fromString("5f78df94-798c-46f5-990a-855b673fbb90")

    val BATTERY: UUID = sig(0x2A19)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val FIRMWARE: UUID = sig(0x2A26)
    val HARDWARE: UUID = sig(0x2A27)

    /**
     * The two commands Bürstli sends, both reads (`03xx`). Never `0202`: the app's library calls it
     * clearRunningDate and sends it after reading the sessions, so it most likely deletes them.
     */
    val QUERY_STATUS = byteArrayOf(0x03, 0x03)
    val QUERY_SESSIONS = byteArrayOf(0x03, 0x07)

    const val RECORD_SIZE = 42

    /** More than any brush keeps (a full buffer had 32); guards against a garbled count. */
    private const val MAX_RECORDS = 100

    /**
     * Models that speak this protocol, by Model Number (2A24): those the app 4.0.8 gives to its
     * type-1 handler (variant 1), plus OCLEANY3MH and OCLEANV1a, confirmed by ha-oclean-integration
     * on real brushes. The X Pro Elite (Y3P) and X Pro 20 send their sessions differently and are
     * left out until someone checks them.
     */
    val TYPE_1 = setOf(
        "OCLEANY3", "OCLEANY3S", "OCLEANY3T", "OCLEANY3M", "OCLEANY3MH", "OCLEANY3MT", "OCLEANY3N",
        "OCLEANY3MN", "OCLEANY3MTN", "OCLEANY3MD", "OCLEANY3D", "OCLEANY3D1", "OCLEANY3D2", "OCLEANR3L",
        "OCLEANV1a",
    )

    /** Retail names, as ha-oclean-integration found them on real brushes. */
    private val RETAIL_NAMES = mapOf(
        "OCLEANY3M" to "Oclean X",
        "OCLEANY3MH" to "Oclean X",
        "OCLEANY3" to "Oclean X Pro",
        "OCLEANY3P" to "Oclean X Pro Elite",
        "OCLEANY3PB" to "Oclean X Pro Digital",
        "OCLEANX20" to "Oclean X Pro 20",
        "OCLEANV1a" to "Oclean X Ultra",
        "OCLEANV20" to "Oclean X Ultra 20",
        "OCLEANY5" to "Oclean Z1",
    )

    fun text(value: ByteArray): String? =
        String(value, Charsets.US_ASCII).trim { it <= ' ' }.takeIf { it.isNotEmpty() }

    /**
     * The model code newer brushes put in their Hardware Revision (2A27): ASCII "HH", then the code
     * as two bytes ("0004"). Null if the brush doesn't, or says "0000"; the Model Number counts then.
     */
    fun modelCode(hardware: ByteArray): String? {
        if (hardware.size < 6 || hardware[0] != 'H'.code.toByte() || hardware[1] != 'H'.code.toByte()) return null
        return hex(hardware, 2, 2).takeIf { it != "0000" }
    }

    /** What to show: the retail name if known, else what the brush calls itself. */
    fun displayName(modelNumber: String?, code: String?): String? = when {
        code != null -> "Oclean $code"
        modelNumber == null -> null
        else -> RETAIL_NAMES[modelNumber] ?: modelNumber
    }

    /** True if Bürstli may send the type-1 read commands to this brush. */
    fun isType1(modelNumber: String?, code: String?): Boolean = code == null && modelNumber in TYPE_1

    /** Battery percent from a reply to [QUERY_STATUS]: `03 03`, then byte 3 of the payload. */
    fun statusBattery(value: ByteArray): Int? {
        if (value.size < 6 || value[0] != 0x03.toByte() || value[1] != 0x03.toByte()) return null
        return (value[5].toInt() and 0xFF).takeIf { it in 0..100 }
    }

    /** One brushing session and the score the brush gave it, if the record carries one. */
    data class Session(val record: BrushingRecord, val score: Int?)

    /**
     * A session record: start (year − 2000, month, day, hour, minute, second), program, the set
     * time (2 bytes, big-endian, seconds), the time actually brushed (2 bytes), …, score (byte 33).
     * Short records, as a brush sends its newest session inline, stop after the time brushed.
     * The start is the brush's own clock, read in [zone]. Null if the record makes no sense.
     */
    fun session(data: ByteArray, offset: Int, length: Int, number: Int, zone: ZoneId): Session? {
        if (length < 11 || offset + length > data.size) return null
        fun u8(i: Int) = data[offset + i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (u8(0) == 0) return null // no session in this slot
        val startedAt = try {
            LocalDateTime.of(2000 + u8(0), u8(1), u8(2), u8(3), u8(4), u8(5)).atZone(zone).toInstant()
        } catch (_: DateTimeException) {
            return null
        }
        val target = u16(7)
        val valid = u16(9)
        // The time brushed; some brushes leave it empty, then the set time is all there is.
        val brushed = when {
            valid > 0 && (target == 0 || valid <= target) -> valid
            target > 0 -> target
            else -> return null
        }
        val score = if (length >= 34) u8(33).takeIf { it in 0..100 } else null
        val record = BrushingRecord(
            number = number,
            brushedSeconds = brushed,
            startedAt = startedAt,
            targetSeconds = target.takeIf { it > 0 },
        )
        return Session(record, score)
    }

    /**
     * Puts the sessions together from notifications on [SESSIONS]. The first one starts with
     * `03 07`, ASCII `*B#` and the number of records (2 bytes, big-endian), then the records,
     * [RECORD_SIZE] bytes each, continued in the following notifications. A count of 0 with data
     * behind it is the newest session inline, cut short. Real brushes also send a count of 1 with
     * only that short record (ha-oclean-integration); [flush] reads what came when nothing follows.
     */
    class SessionStream(private val zone: ZoneId) {
        private var buffer: ByteArrayOutputStream? = null
        private var expected = 0

        /** The sessions once a transfer is complete, in the brush's order; null until then. */
        fun accept(value: ByteArray): List<Session>? {
            if (isHeader(value)) {
                val count = ((value[5].toInt() and 0xFF) shl 8) or (value[6].toInt() and 0xFF)
                val rest = value.copyOfRange(7, value.size)
                buffer = null
                if (count == 0) return records(rest)
                if (count > MAX_RECORDS) return emptyList()
                expected = count * RECORD_SIZE
                buffer = ByteArrayOutputStream().also { it.write(rest) }
            } else {
                (buffer ?: return null).write(value)
            }
            val bytes = buffer!!.toByteArray()
            if (bytes.size < expected) return null
            buffer = null
            return records(bytes.copyOf(expected))
        }

        /** The brush stopped sending: the records that came, the last one maybe short; null if none. */
        fun flush(): List<Session>? {
            val bytes = buffer?.toByteArray() ?: return null
            buffer = null
            return records(bytes)
        }

        private fun records(bytes: ByteArray): List<Session> =
            (bytes.indices step RECORD_SIZE).mapNotNull { from ->
                session(bytes, from, minOf(RECORD_SIZE, bytes.size - from), from / RECORD_SIZE + 1, zone)
            }

        private fun isHeader(v: ByteArray) =
            v.size >= 7 && v[0] == 0x03.toByte() && v[1] == 0x07.toByte() &&
                v[2] == '*'.code.toByte() && v[3] == 'B'.code.toByte() && v[4] == '#'.code.toByte()
    }

    private fun hex(b: ByteArray, from: Int, count: Int) =
        (from until from + count).joinToString("") { String.format("%02X", b[it].toInt() and 0xFF) }
}
