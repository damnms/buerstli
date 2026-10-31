package io.github.damnms.buerstli.capture

/**
 * When the notification phase of a report ends. A normal report listens for [standardSeconds];
 * chosen before the start, a recording runs [untilStopped] by its owner, at most [maxSeconds].
 * Plain Kotlin, so it can be tested without Bluetooth.
 */
class RecordingClock(
    val untilStopped: Boolean = false,
    private val standardSeconds: Int = 30,
    private val maxSeconds: Int = 600,
) {
    @Volatile var elapsed = 0
        private set
    @Volatile private var finished = false

    /** "Stop now" – ends the recording, the report is kept. */
    fun finish() {
        finished = true
    }

    fun shouldContinue(linkDown: Boolean): Boolean = when {
        linkDown || finished -> false
        else -> elapsed < limit
    }

    fun tick() {
        elapsed++
    }

    val secondsLeft: Int get() = (limit - elapsed).coerceAtLeast(0)
    val progress: Float get() = elapsed.toFloat() / limit

    /** A long recording may keep more; the cap keeps the report small enough to share as text. */
    val notificationLimit: Int get() = if (untilStopped) LONG_NOTIFICATIONS else STANDARD_NOTIFICATIONS

    private val limit get() = if (untilStopped) maxSeconds else standardSeconds

    private companion object {
        const val STANDARD_NOTIFICATIONS = 500
        const val LONG_NOTIFICATIONS = 2000
    }
}
