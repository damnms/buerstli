package io.github.damnms.buerstli.drivers.laifen

/**
 * The long frame of the newer Laifen brushes (Wave Pro, SE family, kids' TC51), as described in
 * the appendix of laifen-toolbox/docs/PROTOCOL.md (read from the vendor app):
 *
 *   start | function << 14 | command (16 bit BE) | counter | length (16 bit BE) | payload | xor
 *
 * function 0 write, 1 read, 2 report, 3 answer. The Home Assistant integration's Wave Pro
 * packets ("5A C1 03 …", "5A 81 03 …"), observed on a real brush, are exactly this: answer and
 * report of command 0x0103. Commands start with AA, frames from the brush with 5A.
 */
class LaifenFrame(
    val start: Int,
    val function: Function,
    val command: Int,
    val counter: Int,
    val payload: ByteArray,
    /** XOR of all previous bytes, as for commands. Not confirmed for frames from the brush. */
    val checksumOk: Boolean,
) {
    enum class Function { WRITE, READ, REPORT, ANSWER }

    companion object {
        private const val HEADER = 6

        fun decode(data: ByteArray): LaifenFrame? {
            if (data.size < HEADER + 1) return null
            val u = IntArray(data.size) { data[it].toInt() and 0xFF }
            if (u[0] != 0x5A && u[0] != 0xAA) return null
            val word = (u[1] shl 8) or u[2]
            val length = (u[4] shl 8) or u[5]
            val csIndex = HEADER + length
            if (data.size <= csIndex) return null
            // A V1 state "AA 0A 02 14 01 03 …" read this way claims a length of 0x0103 (259): too long
            // for its 25 bytes, so it is rejected above.
            var cs = 0
            for (i in 0 until csIndex) cs = cs xor u[i]
            return LaifenFrame(
                start = u[0],
                function = Function.entries[word ushr 14],
                command = word and 0x3FFF,
                counter = u[3],
                payload = data.copyOfRange(HEADER, csIndex),
                checksumOk = cs == u[csIndex],
            )
        }
    }
}
