package io.github.damnms.buerstli

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {
    private var now = 0L
    private val diag = Diagnostics(clock = { now }, capacity = 3)

    @Test
    fun `times are relative to the last mark`() {
        now = 10_000
        diag.mark("find")
        now = 12_345
        diag.log("found")
        assertEquals(listOf("  0.00s  find", "  2.35s  found"), diag.lines.value)
    }

    @Test
    fun `only the newest lines are kept`() {
        diag.mark("a"); diag.log("b"); diag.log("c"); diag.log("d")
        assertEquals(listOf("b", "c", "d"), diag.lines.value.map { it.trim().substringAfter("s  ") })
    }
}
