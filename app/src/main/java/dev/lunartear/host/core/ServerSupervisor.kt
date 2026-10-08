package dev.lunartear.host.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IDLE is the state before anything has been started; STOPPED is a terminal
 * state after a run. The distinction matters: the service must not tear itself
 * down just because the supervisor has not started yet.
 */
enum class Phase { IDLE, MIGRATING, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

data class ServerState(
    val phase: Phase = Phase.IDLE,
    val detail: String = "",
    val since: Long = 0L,
    val restarts: Int = 0,
    val grpcPort: Int = 0,
    val cdnPort: Int = 0,
    val host: String = "",
)

/** Addresses the *client* must be pointed at; shown in the UI and used for patching. */
data class ServerAddresses(val host: String, val grpcPort: Int, val cdnPort: Int) {
    val grpcEndpoint: String get() = "$host:$grpcPort"
    val cdnBaseUrl: String get() = "http://$host:$cdnPort"
}

private class StopRequested : Exception("stop requested")

/**
 * Brings the server stack up, keeps it up, and tears it down.
 *
 * Lifecycle: migrate -> CDN -> game server -> monitor. Every step runs on one
 * dedicated thread using blocking calls, which keeps the ordering guarantees
 * obvious (the game server must not start before its SQLite schema exists, and
 * not before the CDN is accepting connections, because it hands the client the
 * CDN URL during login).
 *
 * The upstream binaries are launched with the asset root as their working
 * directory: `cmd/lunar-tear` reads `assets/release/20240404193219.bin.e`
 * relative to cwd, and `octo-cdn` resolves `<assets-dir>/assets/revisions/...`.
 */
class ServerSupervisor(
    private val context: Context,
    val log: LogBus,
) {
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val dbDir = File(context.filesDir, "db")
    private val adminToken: String = randomToken()

    /**
     * Stable HMAC secret for the auth server. Generated once and kept in the
     * app's private storage so tokens issued before a restart stay valid.
     */
    private val authSecret: String by lazy {
        val file = File(context.filesDir, "auth-secret")
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            randomToken().also { runCatching { file.writeText(it) } }
        }
    }

    private var cdn: ServerProcess? = null
    private var game: ServerProcess? = null
    private var auth: ServerProcess? = null
    private var worker: Thread? = null
    private val stopping = AtomicBoolean(false)

    val isBusy: Boolean get() = worker?.isAlive == true

    fun binary(role: ServerRole): File = File(nativeDir, role.fileName)

    // ------------------------------------------------------------------ preflight

    /** Everything that would stop a start, so the UI can fix it before trying. */
    fun preflight(config: ServerConfig): List<String> {
        val problems = mutableListOf<String>()
        val rootPath = config.assetRoot
        if (rootPath.isNullOrBlank()) {
            problems += "No asset folder selected."
            return problems
        }
        val root = File(rootPath)
        if (!root.isDirectory) {
            problems += "Asset folder does not exist: $rootPath"
            return problems
        }
        if (!root.canRead()) problems += "Asset folder is not readable: $rootPath"

        val report = AssetLayout.validate(root)
        if (!report.usable) {
            problems += "Asset tree incomplete (needs assets/release/${AssetLayout.MASTER_DATA_NAME} " +
                "and an assets/revisions/0 tree with list.bin, assetbundle/ and resources/)."
        }
        problems += report.warnings

        val roles = mutableListOf(ServerRole.MIGRATE, ServerRole.CDN, ServerRole.GAME)
        if (config.authPort in 1..65535) roles += ServerRole.AUTH
        for (role in roles) {
            val f = binary(role)
            if (!f.isFile) problems += "Missing bundled binary ${role.fileName}"
            else if (!f.canExecute()) problems += "Bundled binary not executable: ${role.fileName}"
        }

        if (NetInfo.portInUse(config.cdnPort)) problems += "Port ${config.cdnPort} is already in use"
        if (NetInfo.portInUse(config.grpcPort)) problems += "Port ${config.grpcPort} is already in use"
        if (config.authPort in 1..65535 && NetInfo.portInUse(config.authPort)) {
            problems += "Port ${config.authPort} (auth server) is already in use"
        }

        if (config.hostMode == HostMode.LAN && NetInfo.preferredLanAddress() == null) {
            problems += "No LAN address found (not connected to Wi-Fi?)"
        }
        if (config.hostMode == HostMode.CUSTOM && config.customHost.isBlank()) {
            problems += "Custom host is empty"
        }
        return problems
    }

    fun resolveHost(config: ServerConfig): String = when (config.hostMode) {
        HostMode.LOOPBACK -> "127.0.0.1"
        HostMode.LAN -> NetInfo.preferredLanAddress() ?: "127.0.0.1"
        HostMode.CUSTOM -> config.customHost.trim()
    }

    fun addresses(config: ServerConfig) =
        ServerAddresses(resolveHost(config), config.grpcPort, config.cdnPort)

    // ------------------------------------------------------------------ control

    fun start(config: ServerConfig) {
        if (worker?.isAlive == true) {
            log.append("supervisor", "already running; ignoring start")
            return
        }
        stopping.set(false)
        worker = Thread({ runLifecycle(config) }, "supervisor").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (worker == null) {
            _state.value = ServerState(Phase.STOPPED, "stopped")
            return
        }
        stopping.set(true)
        _state.value = _state.value.copy(phase = Phase.STOPPING, detail = "stopping")
        // Nudge any blocking wait: kill the children so the worker unblocks fast.
        cdn?.let { if (it.isAlive) it.stop(1_000) }
        game?.let { if (it.isAlive) it.stop(1_000) }
        auth?.let { if (it.isAlive) it.stop(1_000) }
        runCatching { worker?.join(15_000) }
    }

    /** Asks the running game server to re-read the master data file without a restart. */
    fun reloadMasterData(config: ServerConfig): Boolean {
        val url = URL("http://127.0.0.1:${config.adminPort}/api/admin/master-data/reload")
        return runCatching {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $adminToken")
                connectTimeout = 5_000
                readTimeout = 15_000
                doOutput = true
                outputStream.use { it.write(ByteArray(0)) }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            log.append("supervisor", "master-data reload -> HTTP $code ${body.take(300)}")
            connection.disconnect()
            code in 200..299
        }.getOrElse {
            log.append("supervisor", "master-data reload failed: ${it.message}")
            false
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun runLifecycle(config: ServerConfig) {
        var attempt = 0
        var lastError: String? = null

        while (!stopping.get()) {
            try {
                bringUp(config)
            } catch (_: StopRequested) {
                tearDown()
                _state.value = ServerState(Phase.STOPPED, "stopped")
                return
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                log.append("supervisor", "startup failed: $lastError")
                tearDown()
                if (!retry(config, ++attempt, lastError)) return
                continue
            }

            val reason = monitor()
            if (stopping.get()) {
                tearDown()
                _state.value = ServerState(Phase.STOPPED, "stopped")
                return
                }
            log.append("supervisor", "server exited unexpectedly: $reason")
            tearDown()
            if (!retry(config, ++attempt, reason)) return
        }
    }

    /** Returns false when the supervisor gave up (state already set to FAILED). */
    private fun retry(config: ServerConfig, attempt: Int, reason: String): Boolean {
        if (stopping.get()) return false
        if (!config.autoRestart) {
            _state.value = ServerState(Phase.FAILED, reason)
            return false
        }
        if (attempt > MAX_ATTEMPTS) {
            _state.value = ServerState(Phase.FAILED, "gave up after $MAX_ATTEMPTS attempts: $reason", restarts = attempt - 1)
            return false
        }
        val backoff = BACKOFF_MS.getOrElse(attempt - 1) { BACKOFF_MS.last() }
        _state.value = ServerState(
            phase = Phase.STARTING,
            detail = "retrying in ${backoff / 1000}s (attempt $attempt of $MAX_ATTEMPTS): $reason",
            restarts = attempt - 1,
            grpcPort = config.grpcPort,
            cdnPort = config.cdnPort,
        )
        sleepInterruptible(backoff)
        return !stopping.get()
    }

    private fun bringUp(config: ServerConfig) {
        checkNotStopping()
        val rootPath = config.assetRoot ?: throw IllegalStateException("no asset folder selected")
        val root = File(rootPath)
        val report = AssetLayout.validate(root)
        if (!report.usable) throw IllegalStateException("asset tree incomplete under $rootPath")

        val host = resolveHost(config)
        val addresses = ServerAddresses(host, config.grpcPort, config.cdnPort)
        val dbFile = File(dbDir, "game.db")

        // 1. migrations -------------------------------------------------------
        _state.value = ServerState(Phase.MIGRATING, "applying database migrations", grpcPort = config.grpcPort, cdnPort = config.cdnPort, host = host)
        dbDir.mkdirs()
        val migrateResult = NativeExec.run(
            listOf(binary(ServerRole.MIGRATE).absolutePath, "--db", dbFile.absolutePath, "--mode", "up", "--quiet"),
            timeoutMs = 120_000,
        )
        log.append("migrations", migrateResult.summary(1_500))
        if (migrateResult.timedOut) throw IllegalStateException("migrations timed out")
        if (migrateResult.exitCode != 0) throw IllegalStateException("migrations failed (exit ${migrateResult.exitCode})")
        checkNotStopping()

        // 2. CDN --------------------------------------------------------------
        _state.value = ServerState(Phase.STARTING, "starting Octo CDN on port ${config.cdnPort}", grpcPort = config.grpcPort, cdnPort = config.cdnPort, host = host)
        val cdnProcess = ServerProcess(
            role = ServerRole.CDN,
            command = listOf(
                binary(ServerRole.CDN).absolutePath,
                "--listen", "0.0.0.0:${config.cdnPort}",
                "--public-addr", "${addresses.host}:${config.cdnPort}",
                "--assets-dir", root.absolutePath,
            ),
            workingDir = root,
            extraEnv = emptyMap(),
            log = log,
        )
        cdnProcess.onExit = { role, code -> log.append("supervisor", "${role.label} exited with $code") }
        cdn = cdnProcess
        cdnProcess.start()
        if (!awaitTcp(config.cdnPort, 20_000)) {
            throw IllegalStateException("CDN did not start listening on ${config.cdnPort} (see log)")
        }
        log.append("supervisor", "CDN ready at ${addresses.cdnBaseUrl}")
        checkNotStopping()

        // 3. auth server (fake Facebook OAuth) --------------------------------
        // The patched client's Facebook SDK is rewritten to point here, and the
        // game server validates the tokens it hands out. It has to be up before
        // the game server, which is given --auth-url.
        if (config.authPort in 1..65535) {
            _state.value = ServerState(
                Phase.STARTING,
                "starting auth server on port ${config.authPort}",
                grpcPort = config.grpcPort,
                cdnPort = config.cdnPort,
                host = host,
            )
            val authProcess = ServerProcess(
                role = ServerRole.AUTH,
                command = listOf(
                    binary(ServerRole.AUTH).absolutePath,
                    // [::] rather than 0.0.0.0: the client reaches the auth server
                    // over loopback by whatever literal it was patched with, and a
                    // v6 socket also accepts v4-mapped connections, so 127.0.0.1 and
                    // [::1] both work.
                    "--listen", "[::]:${config.authPort}",
                    "--db", File(dbDir, "auth.db").absolutePath,
                    "--secret", authSecret,
                ),
                workingDir = root,
                extraEnv = emptyMap(),
                log = log,
            )
            authProcess.onExit = { role, code -> log.append("supervisor", "${role.label} exited with $code") }
            auth = authProcess
            authProcess.start()
            if (!awaitTcp(config.authPort, 20_000)) {
                throw IllegalStateException("auth server did not start listening on ${config.authPort} (see log)")
            }
            log.append(
                "supervisor",
                "auth server ready (Facebook redirect target ${config.patchAuthHost ?: "disabled"})",
            )
            checkNotStopping()
        }

        // 4. game server ------------------------------------------------------
        _state.value = ServerState(Phase.STARTING, "starting game server on port ${config.grpcPort}", grpcPort = config.grpcPort, cdnPort = config.cdnPort, host = host)
        val gameCommand = mutableListOf(
            binary(ServerRole.GAME).absolutePath,
            "--listen", "0.0.0.0:${config.grpcPort}",
            "--public-addr", "${addresses.host}:${config.grpcPort}",
            "--octo-url", addresses.cdnBaseUrl,
            "--db", dbFile.absolutePath,
            "--admin-listen", "127.0.0.1:${config.adminPort}",
        )
        // Account-linking RPCs validate a Facebook token by calling
        // <auth-url>/me?access_token=… (see upstream user.go resolveAuthToken),
        // so the game server needs to know where the auth server is.
        if (config.authPort in 1..65535) {
            gameCommand += listOf("--auth-url", "http://127.0.0.1:${config.authPort}")
        }
        val gameProcess = ServerProcess(
            role = ServerRole.GAME,
            command = gameCommand,
            workingDir = root,
            extraEnv = mapOf("LUNAR_ADMIN_TOKEN" to adminToken),
            log = log,
        )
        gameProcess.onExit = { role, code -> log.append("supervisor", "${role.label} exited with $code") }
        game = gameProcess
        gameProcess.start()
        // Loading the master data takes a few seconds on a phone.
        if (!awaitTcp(config.grpcPort, 90_000)) {
            throw IllegalStateException("game server did not start listening on ${config.grpcPort} (see log)")
        }
        log.append("supervisor", "game server ready at ${addresses.grpcEndpoint}")
        checkNotStopping()

        _state.value = ServerState(
            phase = Phase.RUNNING,
            detail = "serving ${root.name} · client connects to ${addresses.host}",
            since = System.currentTimeMillis(),
            grpcPort = config.grpcPort,
            cdnPort = config.cdnPort,
            host = host,
        )
    }

    /** Blocks until something dies or a stop is requested. */
    private fun monitor(): String {
        while (!stopping.get()) {
            val cdnState = cdn
            val gameState = game
            if (cdnState != null && !cdnState.isAlive) {
                return "cdn exited (code ${cdnState.exitCode})"
            }
            if (gameState != null && !gameState.isAlive) {
                return "game server exited (code ${gameState.exitCode})"
            }
            val authState = auth
            if (authState != null && !authState.isAlive) {
                return "auth server exited (code ${authState.exitCode})"
            }
            Thread.sleep(400)
        }
        return "stop requested"
    }

    private fun tearDown() {
        game?.stop()
        cdn?.stop()
        auth?.stop()
        game = null
        cdn = null
        auth = null
    }

    private fun checkNotStopping() {
        if (stopping.get()) throw StopRequested()
    }

    private fun awaitTcp(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopping.get()) throw StopRequested()
            if (NetInfo.tcpReachable("127.0.0.1", port)) return true
            // Fail fast if the child died instead of waiting out the timeout.
            val dead = listOfNotNull(cdn, game, auth).firstOrNull { !it.isAlive && it.exitReported }
            if (dead != null) return false
            Thread.sleep(250)
        }
        return false
    }

    private fun sleepInterruptible(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline && !stopping.get()) {
            Thread.sleep(200)
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val BACKOFF_MS = longArrayOf(5_000, 15_000, 60_000)

        fun randomToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
