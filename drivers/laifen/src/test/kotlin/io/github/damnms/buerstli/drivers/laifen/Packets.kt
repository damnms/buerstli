package io.github.damnms.buerstli.drivers.laifen

/** Packets captured with nRF Connect from a real LFTB01-P (2026-10-29). */
object Packets {
    fun hex(s: String): ByteArray = s.split("-", " ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()

    val MODE2_ACTIVE = hex("AA-0A-02-14-01-03-01-05-04-06-06-06-06-06-0B-06-06-00-5E-00-00-78-00-00-9F")
    val MODE3_ACTIVE = hex("AA-0A-02-14-02-03-01-05-04-06-06-06-06-06-0B-06-06-00-5E-00-00-78-00-00-9C")
    val AFTER_WRITE = hex("AA-0A-02-14-02-03-01-05-04-06-06-06-06-05-0B-06-06-00-5E-00-00-78-00-00-9F")

    /** The command that was written by hand and accepted by the brush. */
    val SET_MODE3_666_TO_665 = hex("AA-04-01-04-02-06-06-05-AC")
}
