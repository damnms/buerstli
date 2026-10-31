package io.github.damnms.buerstli.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `until stopped keeps listening past 30 seconds, up to 10 minutes`() {
        val clock = RecordingClock()
        repeat(10) { clock.tick() }
        clock.keepGoing()
        assertTrue(clock.untilStopped)
        assertEquals(600, runOut(clock))
    }

    @Test
    fun `finishing ends the recording right away, and the report is kept`() {
        val clock = RecordingClock().apply { keepGoing() }
        repeat(205) { clock.tick() }
        clock.finish()
        assertFalse(clock.shouldContinue(linkDown = false))
        assertEquals(205, clock.elapsed)
    }

    @Test
    fun `a brush that falls asleep ends the recording`() {
        val clock = RecordingClock().apply { keepGoing() }
        assertEquals(42, runOut(clock) { clock.elapsed >= 42 })
    }

    @Test
    fun `progress and time left follow the chosen limit`() {
        val clock = RecordingClock()
        repeat(15) { clock.tick() }
        assertEquals(15, clock.secondsLeft)
        assertEquals(0.5f, clock.progress, 0.001f)

        clock.keepGoing()
        assertEquals(585, clock.secondsLeft)
        assertEquals(0.025f, clock.progress, 0.001f)
    }

    @Test
    fun `a long recording may keep more notifications`() {
        val clock = RecordingClock()
        assertEquals(500, clock.notificationLimit)
        clock.keepGoing()
        assertEquals(2000, clock.notificationLimit)
    }
}
