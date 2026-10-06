package dev.lunartear.host.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** One log line with the process it came from. */
data class LogLine(val source: String, val text: String, val at: Long)

/** Immutable view handed to the UI; [version] changes whenever [lines] does. */
data class LogSnapshot(val version: Long, val lines: List<LogLine>)

/**
 * Bounded, thread-safe log sink shared by every child process.
 *
 * Lines arrive from reader threads; the UI observes a throttled snapshot so a
 * chatty server cannot trigger a recomposition per line. Optionally mirrors
 * everything to a file so the user can export a session.
 */
class LogBus(
    private val capacity: Int = 4_000,
    private val flushIntervalMs: Long = 250,
    private val file: File? = null,
) {
    private val buffer = ArrayDeque<LogLine>(capacity)
    private val lock = Any()
    private var version = 0L
    private val _snapshot = MutableStateFlow(LogSnapshot(0, emptyList()))
    val snapshot: StateFlow<LogSnapshot> = _snapshot.asStateFlow()

    private var writer: BufferedWriter? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    init {
        val target = file
        if (target != null) {
            runCatching {
                target.parentFile?.mkdirs()
                writer = target.bufferedWriter()
            }
        }
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(flushIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                publishIfDirty()
            }
        }.apply { isDaemon = true; name = "logbus-flush"; start() }
    }

    fun append(source: String, text: String) {
        val line = LogLine(source, text, System.currentTimeMillis())
        synchronized(lock) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(line)
            version++
        }
        runCatching { writer?.write("${stamp.format(Date(line.at))} [$source] $text\n"); writer?.flush() }
    }

    /** Multi-line blob (e.g. a Go binary's usage output) into individual lines. */
    fun appendBlock(source: String, block: String) {
        block.split('\n').forEachIndexed { index, line ->
            if (line.isNotEmpty() || index < block.length) append(source, line)
        }
    }

    fun tail(max: Int = 600): List<LogLine> = synchronized(lock) {
        if (buffer.size <= max) buffer.toList() else buffer.toList().takeLast(max)
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        version++
        publishIfDirty()
    }

    fun close() = runCatching { writer?.close() }.let { }

    private fun publishIfDirty() {
        val current: LogSnapshot
        synchronized(lock) {
            if (_snapshot.value.version == version) return
            current = LogSnapshot(version, if (buffer.size <= 800) buffer.toList() else buffer.toList().takeLast(800))
        }
        _snapshot.value = current
    }
}
