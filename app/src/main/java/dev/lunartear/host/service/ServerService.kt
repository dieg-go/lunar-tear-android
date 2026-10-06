package dev.lunartear.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.lunartear.host.MainActivity
import dev.lunartear.host.R
import dev.lunartear.host.core.LunarHost
import dev.lunartear.host.core.Phase
import dev.lunartear.host.core.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the game server stack alive in the background.
 *
 * The servers are child processes of this app, so the process must stay
 * resident: the service holds a wake lock (the CPU must not sleep while a
 * client is downloading) and a Wi-Fi lock (so the radio stays on for LAN play).
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LunarHost.initialize(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                LunarHost.supervisor.stop()
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_RELOAD -> {
                val config = LunarHost.config.value
                scope.launch(Dispatchers.IO) { LunarHost.supervisor.reloadMasterData(config) }
                return START_STICKY
            }
        }

        startForegroundWithType(buildNotification(LunarHost.supervisor.state.value))
        acquireLocks()
        LunarHost.supervisor.start(LunarHost.config.value)

        scope.launch {
            LunarHost.supervisor.state.collectLatest { state ->
                notify(buildNotification(state))
                // Terminal states only: IDLE is simply "not started yet", and
                // acting on it would kill the service before the supervisor
                // has done anything.
                if (state.phase == Phase.STOPPED || state.phase == Phase.FAILED) {
                    shutdown()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        LunarHost.supervisor.stop()
        releaseLocks()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers

    private fun shutdown() {
        scope.cancel()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithType(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lunar-tear:servers").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                },
                "lunar-tear:cdn",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_desc) }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ServerState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (state.phase) {
            Phase.IDLE -> getString(R.string.notif_idle)
            Phase.MIGRATING -> getString(R.string.notif_migrating)
            Phase.STARTING -> getString(R.string.notif_starting)
            Phase.RUNNING -> getString(R.string.notif_running)
            Phase.STOPPING -> getString(R.string.notif_stopping)
            Phase.STOPPED -> getString(R.string.notif_idle)
            Phase.FAILED -> getString(R.string.notif_failed)
        }
        val text = when {
            state.phase == Phase.RUNNING ->
                "${state.host}:${state.grpcPort} · cdn ${state.cdnPort}"
            state.detail.isNotBlank() -> state.detail
            else -> getString(R.string.notif_no_detail)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.lunartear.host.action.START"
        const val ACTION_STOP = "dev.lunartear.host.action.STOP"
        const val ACTION_RELOAD = "dev.lunartear.host.action.RELOAD"

        private const val CHANNEL_ID = "lunar-tear-servers"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun reloadMasterData(context: Context) {
            val intent = Intent(context, ServerService::class.java).setAction(ACTION_RELOAD)
            context.startService(intent)
        }
    }
}
