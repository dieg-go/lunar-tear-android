package dev.lunartear.host.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.lunartear.host.core.AssetLayout
import dev.lunartear.host.core.AssetReport
import dev.lunartear.host.core.HostMode
import dev.lunartear.host.core.LunarHost
import dev.lunartear.host.core.NetInfo
import dev.lunartear.host.core.Phase
import dev.lunartear.host.core.StorageAccess
import dev.lunartear.host.service.ServerService
import java.io.File

private val Ok = Color(0xFF7FD68A)
private val Warn = Color(0xFFFFCF7F)

@Composable
fun ServerScreen(onPickFolder: () -> Unit) {
    val context = LocalContext.current
    val config by LunarHost.config.collectAsState()
    val state by LunarHost.supervisor.state.collectAsState()

    var report by remember(config.assetRoot) {
        mutableStateOf(config.assetRoot?.let { AssetLayout.validate(File(it)) })
    }
    var problems by remember(config, report) {
        mutableStateOf(LunarHost.supervisor.preflight(config))
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the service still runs; the notification is simply hidden */ }

    // "All files access" is granted in Settings, so this has to be re-read when the
    // screen resumes: otherwise the card below keeps asking for a permission the user
    // has already given, until the app is restarted.
    val allFiles = remember { mutableStateOf(StorageAccess.hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFiles.value = StorageAccess.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val addresses = LunarHost.supervisor.addresses(config)
    val running = state.phase == Phase.RUNNING

    ScreenScroll {
        SectionCard("Server") {
            val (phaseText, accent) = when (state.phase) {
                Phase.IDLE -> "stopped" to MaterialTheme.colorScheme.secondary
                Phase.MIGRATING -> "applying migrations" to Warn
                Phase.STARTING -> "starting" to Warn
                Phase.RUNNING -> "running" to Ok
                Phase.STOPPING -> "stopping" to Warn
                Phase.STOPPED -> "stopped" to MaterialTheme.colorScheme.secondary
                Phase.FAILED -> "failed" to MaterialTheme.colorScheme.error
            }
            Text(phaseText, color = accent, fontWeight = FontWeight.SemiBold)
            if (state.detail.isNotBlank()) Mono(state.detail)
            if (state.restarts > 0) Line("restarts", state.restarts.toString())
            if (running) {
                Line("client gRPC", addresses.grpcEndpoint)
                Line("client CDN", addresses.cdnBaseUrl)
                Line("uptime", uptime(state.since))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && state.phase != Phase.STARTING && state.phase != Phase.MIGRATING,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        ServerService.start(context)
                    },
                ) { Text("Start server") }

                OutlinedButton(
                    enabled = running || state.phase == Phase.STARTING || state.phase == Phase.MIGRATING,
                    onClick = { ServerService.stop(context) },
                ) { Text("Stop") }

                OutlinedButton(enabled = running, onClick = { ServerService.reloadMasterData(context) }) {
                    Text("Reload master data")
                }
            }
        }

        if (!allFiles.value) {
            SectionCard("Storage access") {
                Text(
                    "The servers are separate processes and need real filesystem paths, so they " +
                        "cannot read through Android's file picker. Grant \"All files access\" and " +
                        "point the app at a folder.",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Button(onClick = {
                    StorageAccess.allFilesAccessIntent(context)?.let { context.startActivity(it) }
                }) { Text("Grant all files access") }
            }
        }

        SectionCard("Asset folder") {
            Line("path", config.assetRoot ?: "not set")
            val r = report
            if (r != null) {
                Line("tree", r.treeKind ?: "none found")
                Line("revisions", "${r.revisionCount}${if (r.revisionDirs.isNotEmpty()) " (${r.revisionDirs.first()}..${r.revisionDirs.last()})" else ""}")
                Line("usable", if (r.usable) "yes" else "no")
                r.hints.forEach { Text(it, color = Warn, fontSize = 11.sp) }
                r.warnings.forEach { Text(it, color = Warn, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPickFolder) { Text("Choose folder") }
                OutlinedButton(onClick = {
                    report = config.assetRoot?.let { AssetLayout.validate(File(it)) }
                    problems = LunarHost.supervisor.preflight(config)
                }) { Text("Re-check") }
            }
            if (report?.hints?.isNotEmpty() == true && config.assetRoot != null) {
                val root = File(config.assetRoot!!)
                TextButton(onClick = {
                    AssetLayout.relocateDumpRoot(root)
                        .onSuccess {
                            LunarHost.log.append("ui", "relocated revisions/ -> assets/revisions/")
                            report = AssetLayout.validate(root)
                            problems = LunarHost.supervisor.preflight(LunarHost.config.value)
                        }
                        .onFailure { LunarHost.log.append("ui", "relocate failed: ${it.message}") }
                }) { Text("Fix layout (rename revisions/ into assets/)") }
            }
        }

        SectionCard("Addresses") {
            Text(
                "These are baked into the patched client APK. Changing them here without " +
                    "re-patching the client will make it fail to connect.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HostMode.entries.forEach { mode ->
                    val selected = config.hostMode == mode
                    if (selected) {
                        Button(onClick = { LunarHost.updateConfig { it.copy(hostMode = mode) } }) {
                            Text(mode.label())
                        }
                    } else {
                        OutlinedButton(onClick = { LunarHost.updateConfig { it.copy(hostMode = mode) } }) {
                            Text(mode.label())
                        }
                    }
                }
            }
            Line("resolved host", addresses.host)
            if (config.hostMode == HostMode.LAN) {
                Line("detected", NetInfo.lanAddresses().joinToString(", ").ifEmpty { "none" })
            }
            Line("gRPC port", config.grpcPort.toString())
            Line("CDN port", config.cdnPort.toString())
            Line(
                "auth port",
                if (config.authPort in 1..65535) {
                    "${config.authPort} (Facebook redirect target ${config.patchAuthHost})"
                } else {
                    "disabled - the client will hang on its Facebook SDK call"
                },
            )
            val remembered = config.patchedHost
            if (remembered != null) {
                val mismatch = !config.hostMatchesRememberedPatch(addresses.host) || !config.portsMatchRememberedPatch()
                Line("last patched", "$remembered:${config.patchedGrpcPort} (cdn ${config.patchedCdnPort})")
                if (mismatch) {
                    Text(
                        "Warning: the installed client was patched for the values above; the current " +
                            "configuration differs. Re-patch the client, or change these back.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (problems.isNotEmpty()) {
            SectionCard("Before you start") {
                problems.forEach { Text("• $it", fontSize = 12.sp, color = Warn) }
            }
        }
    }
}

private fun HostMode.label(): String = when (this) {
    HostMode.LOOPBACK -> "This device"
    HostMode.LAN -> "LAN"
    HostMode.CUSTOM -> "Custom"
}

private fun uptime(since: Long): String {
    if (since == 0L) return "—"
    val seconds = (System.currentTimeMillis() - since) / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

