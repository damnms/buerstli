package io.github.damnms.buerstli.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecordingClockTest {

    private fun runOut(clock: RecordingClock, linkDown: () -> Boolean = { false }): Int {
        while (clock.shouldContinue(linkDown())) clock.tick()
        return clock.elapsed
    }

    @Test
    fun `a normal report listens for 30 seconds`() {
        assertEquals(30, runOut(RecordingClock()))
    }

    @Test
    fun `chosen before the start, a recording runs until stopped, at most 10 minutes`() {
        assertEquals(600, runOut(RecordingClock(untilStopped = true)))
    }

    @Test
    fun `finishing ends the recording right away, and the report is kept`() {
        val clock = RecordingClock(untilStopped = true)
        repeat(205) { clock.tick() }
        clock.finish()
        assertFalse(clock.shouldContinue(linkDown = false))
        assertEquals(205, clock.elapsed)
    }

    @Test
    fun `finishing works in any phase, even before listening started`() {
        val clock = RecordingClock(untilStopped = true)
        clock.finish()
        assertEquals(0, runOut(clock))
    }

    @Test
    fun `a brush that falls asleep ends the recording`() {
        val clock = RecordingClock(untilStopped = true)
        assertEquals(42, runOut(clock) { clock.elapsed >= 42 })
    }

    @Test
    fun `progress and time left follow the chosen limit`() {
        val normal = RecordingClock().apply { repeat(15) { tick() } }
        assertEquals(15, normal.secondsLeft)
        assertEquals(0.5f, normal.progress, 0.001f)

        val long = RecordingClock(untilStopped = true).apply { repeat(15) { tick() } }
        assertEquals(585, long.secondsLeft)
        assertEquals(0.025f, long.progress, 0.001f)
    }

    @Test
    fun `a long recording may keep more notifications`() {
        assertEquals(500, RecordingClock().notificationLimit)
        assertEquals(2000, RecordingClock(untilStopped = true).notificationLimit)
    }
}
