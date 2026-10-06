package dev.lunartear.host.core

import java.io.File
import java.util.concurrent.TimeUnit

/** The bundled executables, by role. */
enum class ServerRole(val fileName: String, val label: String) {
    MIGRATE("liblt-migrate.so", "migrations"),
    CDN("liblt-cdn.so", "cdn"),
    GAME("liblt-server.so", "game"),

    /**
     * Fake Facebook OAuth + `/me`, which the patched client's Facebook SDK is
     * redirected to and the game server validates tokens against.
     */
    AUTH("liblt-auth.so", "auth"),

    /** Not a long-running service: the master-data patcher is run on demand. */
    MASTERDATA("liblt-patch-masterdata.so", "masterdata"),
}

/**
 * One supervised child process.
 *
 * The Go binaries handle SIGTERM and shut down gracefully, so [stop] sends a
 * normal destroy first and only escalates to SIGKILL after a grace period.
 */
class ServerProcess(
    val role: ServerRole,
    private val command: List<String>,
    private val workingDir: File,
    private val extraEnv: Map<String, String>,
    private val log: LogBus,
) {
    @Volatile
    private var process: Process? = null

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    var exitReported = false
        private set

    val isAlive: Boolean get() = process?.isAlive == true

    /** Called from the process-watcher thread when the child exits on its own. */
    var onExit: ((ServerRole, Int) -> Unit)? = null

    fun start() {
        val pb = ProcessBuilder(command)
        pb.directory(workingDir)
        pb.redirectErrorStream(true)
        pb.environment().putAll(extraEnv)

        log.append(role.label, "$ ${command.joinToString(" ")}")
        log.append(role.label, "cwd=${workingDir.absolutePath}")

        val started = pb.start()
        process = started
        exitCode = null
        exitReported = false

        Thread {
            runCatching {
                started.inputStream.bufferedReader().forEachLine { line -> log.append(role.label, line) }
            }
        }.apply { isDaemon = true; name = "out-${role.label}"; start() }

        Thread {
            val code = runCatching { started.waitFor() }.getOrDefault(-1)
            exitCode = code
            exitReported = true
            onExit?.invoke(role, code)
        }.apply { isDaemon = true; name = "wait-${role.label}"; start() }
    }

    fun stop(graceMs: Long = 4_000) {
        val current = process ?: return
        if (!current.isAlive) return
        log.append(role.label, "stopping (SIGTERM)…")
        current.destroy()
        if (!current.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
            log.append(role.label, "did not exit in ${graceMs}ms, killing")
            current.destroyForcibly()
            current.waitFor(2, TimeUnit.SECONDS)
        }
        log.append(role.label, "stopped (exit=${runCatching { current.exitValue() }.getOrNull()})")
    }
}
