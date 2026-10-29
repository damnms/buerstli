package io.github.damnms.buerstli

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * A small, local-only log of what happened with the brush, with times relative to the
 * last [mark] (e.g. "find"). Shown in the app and copyable, never sent anywhere.
 */
class Diagnostics(
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 300,
) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile private var start = clock()

    /** Starts a new timeline, e.g. when the user taps "find". */
    fun mark(message: String) {
        start = clock()
        log(message)
    }

    fun log(message: String) {
        val seconds = (clock() - start) / 1000.0
        val line = String.format(Locale.ROOT, "%6.2fs  %s", seconds, message)
        _lines.update { (it + line).takeLast(capacity) }
    }

    fun text(): String = _lines.value.joinToString("\n")
}

fun ByteArray.hex(): String = joinToString("-") { "%02X".format(it.toInt() and 0xFF) }
