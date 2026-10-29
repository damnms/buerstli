package io.github.damnms.buerstli.drivers.laifen

/** Packets captured with nRF Connect from a real LFTB01-P (2026-10-29). */
object Packets {
    fun hex(s: String): ByteArray = s.split("-", " ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()

    val MODE2_ACTIVE = hex("AA-0A-02-14-01-03-01-05-04-06-06-06-06-06-0B-06-06-00-5E-00-00-78-00-00-9F")
    val MODE3_ACTIVE = hex("AA-0A-02-14-02-03-01-05-04-06-06-06-06-06-0B-06-06-00-5E-00-00-78-00-00-9C")
    val AFTER_WRITE = hex("AA-0A-02-14-02-03-01-05-04-06-06-06-06-05-0B-06-06-00-5E-00-00-78-00-00-9F")

    /** After writing [SET_DURATION_150] by hand: brushing duration now 150 s (bytes 20-21). */
    val DURATION_150 = hex("AA-0A-02-14-00-0A-01-01-04-06-06-01-01-0A-0B-06-06-00-5E-00-00-96-00-00-71")

    /** Asks the brush for its state; it answers with a status notification. */
    val READ_STATE = hex("AA-0A-00-00-A0")

    /** What a plain GATT read of 0xFF02 returns on the LFTB01-P: not the state, just filler. */
    val GATT_READ_FILLER = hex("01-02-03-04-05-06-07-02-FF-2A")

    /** Acknowledgement the brush sends after a brushing-time write (seen in the app's log). */
    val ACK_DURATION = hex("AA-05-02-01-00-AC")

    /** Commands that were written by hand and accepted by the brush. */
    val SET_DURATION_150 = hex("AA-05-01-02-00-96-3A")
    val SET_MODE3_666_TO_665 = hex("AA-04-01-04-02-06-06-05-AC")
}
