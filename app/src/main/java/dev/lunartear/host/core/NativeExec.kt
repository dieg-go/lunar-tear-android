package dev.lunartear.host.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result of running one of the bundled native binaries.
 *
 * Exit codes are meaningful on their own: the Go binaries use the standard
 * `flag` package, so `--help` prints usage and exits 2 without touching the
 * network or the filesystem.
 */
data class ExecResult(
    val command: List<String>,
    val exitCode: Int,
    val output: String,
    val durationMs: Long,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = !timedOut && exitCode == 0

    fun summary(maxChars: Int = 4000): String {
        val head = if (output.length > maxChars) output.take(maxChars) + "\n... (${output.length - maxChars} more chars)" else output
        return buildString {
            append("$ ")
            append(command.joinToString(" "))
            append('\n')
            append("exit=")
            append(if (timedOut) "TIMEOUT" else exitCode.toString())
            append("  (")
            append(durationMs)
            append(" ms)\n")
            append(head)
        }
    }
}

/**
 * Runs a native binary that was extracted into nativeLibraryDir.
 *
 * Android 10+ mounts app data directories noexec, so the only way to run a
 * bundled executable is to have it extracted from the APK as `lib*.so` and
 * exec it straight out of `ApplicationInfo.nativeLibraryDir`.
 */
object NativeExec {

    fun run(
        command: List<String>,
        timeoutMs: Long = 20_000,
        workingDir: File? = null,
        extraEnv: Map<String, String> = emptyMap(),
    ): ExecResult {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        if (workingDir != null) pb.directory(workingDir)
        pb.environment().putAll(extraEnv)

        val started = System.currentTimeMillis()
        val process = pb.start()

        // Drain stdout/stderr continuously: the servers are chatty and a full
        // pipe buffer would deadlock the child.
        val sink = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(sink) { sink.append(line).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(1_000)

        val output = synchronized(sink) { sink.toString() }
        return ExecResult(
            command = command,
            exitCode = if (finished) process.exitValue() else -1,
            output = output,
            durationMs = System.currentTimeMillis() - started,
            timedOut = !finished,
        )
    }
}
