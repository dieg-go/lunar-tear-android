package dev.lunartear.host.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide host state: the config, the log sink and the supervisor.
 *
 * The app is a single-process app, so a singleton is the whole story: the
 * service owns the lifecycle (it is what keeps the process alive), and the UI
 * observes the same StateFlows. Nothing here starts the servers on its own -
 * only [dev.lunartear.host.service.ServerService] does that, so the servers can
 * never run without a foreground notification.
 */
object LunarHost {

    private lateinit var appContext: Context
    private var initialised = false

    val log: LogBus by lazy {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        LogBus(file = File(appContext.filesDir, "logs/session-$stamp.log"))
    }

    val supervisor: ServerSupervisor by lazy { ServerSupervisor(appContext, log) }

    val configStore: ConfigStore by lazy { ConfigStore(appContext) }

    private val _config = MutableStateFlow(ServerConfig.DEFAULT)
    val config: StateFlow<ServerConfig> = _config.asStateFlow()

    val logsDir: File get() = File(appContext.filesDir, "logs")
    val dbDir: File get() = File(appContext.filesDir, "db")

    fun initialize(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        initialised = true
        _config.value = configStore.load()
    }

    fun updateConfig(transform: (ServerConfig) -> ServerConfig): ServerConfig {
        val next = transform(_config.value)
        _config.value = next
        configStore.save(next)
        return next
    }

    /** Remembers the host/ports that were baked into the client, for the mismatch warning. */
    fun rememberPatch(host: String, grpcPort: Int, cdnPort: Int) = updateConfig {
        it.copy(patchedHost = host, patchedGrpcPort = grpcPort, patchedCdnPort = cdnPort)
    }
}
