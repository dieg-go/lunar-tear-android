package dev.lunartear.host.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import dev.lunartear.host.core.AssetLayout
import dev.lunartear.host.core.NativeExec
import dev.lunartear.host.core.Phase
import dev.lunartear.host.core.ServerRole
import dev.lunartear.host.core.LunarHost
import dev.lunartear.host.patch.apk.ApkPatcher
import dev.lunartear.host.patch.apk.ApkSigning
import dev.lunartear.host.patch.apk.PatchRecipe
import dev.lunartear.host.patch.apk.PatchReport
import dev.lunartear.host.patch.apk.PatchTarget
import dev.lunartear.host.patch.masterdata.MasterDataTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val WarnAmber = Color(0xFFFFCF7F)
private val OkGreen = Color(0xFF7FD68A)

/**
 * The APK patcher page: pick the client APK, see exactly what will be baked in,
 * patch it, then install the result.
 *
 * The patched APK has a different signing identity from the store/APKMirror
 * build, so installing it over the original is impossible - the original has to
 * be uninstalled first. The UI says so, and never uninstalls anything itself.
 */
@Composable
fun PatchScreen(onPickApk: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by LunarHost.config.collectAsState()

    // The picked APK lives in the config so it survives tab switches and restarts.
    val sourceApk = config.sourceApk?.let(::File)
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var report by remember { mutableStateOf<PatchReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }

    val target = PatchTarget(
        host = LunarHost.supervisor.resolveHost(config),
        grpcPort = config.grpcPort,
        cdnPort = config.cdnPort,
        authHost = config.patchAuthHost,
    )
    val recipeProblems = remember(target) { PatchRecipe.problems(target) }

    ScreenScroll {
        SectionCard("1. Client APK") {
            Text(
                "Pick the NieR Re[in]carnation 3.7.1 (versionCode 152) arm64-v8a APK. " +
                    "It is never uploaded anywhere; everything happens on this device.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Line("selected", sourceApk?.let { "${it.name} (${it.length() / 1024 / 1024} MB)" } ?: "none")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = onPickApk) { Text("Choose APK") }
                OutlinedButton(
                    enabled = !busy && sourceApk != null,
                    onClick = {
                        val apk = sourceApk ?: return@OutlinedButton
                        busy = true
                        report = null
                        scope.launch {
                            problems = withContext(Dispatchers.IO) { ApkPatcher.preflight(apk, target) }
                            busy = false
                        }
                    },
                ) { Text("Check") }
            }
            problems.forEach { Text("• $it", fontSize = 11.sp, color = WarnAmber) }
        }

        SectionCard("2. What will be baked in") {
            Line("gRPC", "${target.host}:${target.grpcPort}")
            Line("CDN", target.cdnBaseUrl)
            Line("port patches", if (target.grpcPort == 443) "none needed" else "3 sites in libil2cpp.so")
            Spacer(Modifier.height(4.dp))
            Text(
                "These match the running server configuration. Change them in the Server tab " +
                    "before patching if you host for another device (LAN mode).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            recipeProblems.forEach { Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        }

        SectionCard("3. Patch") {
            Text(
                "Rewrites global-metadata.dat (server addresses), libil2cpp.so (SSL/encryption/IAP " +
                    "patches + gRPC port) and adds android:usesCleartextTraffic to the manifest, then " +
                    "re-zips, aligns and signs the APK.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(6.dp))
            Button(
                enabled = !busy && sourceApk != null && recipeProblems.isEmpty(),
                onClick = {
                    val apk = sourceApk ?: return@Button
                    busy = true
                    report = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val keyStore = ApkSigning.loadKeystore(context)
                                val outDir = File(context.filesDir, "patched").apply { mkdirs() }
                                ApkPatcher.patch(
                                    keyStore = keyStore,
                                    sourceApk = apk,
                                    outputApk = File(outDir, "nier-reincarnation-3.7.1-lunartear.apk"),
                                    workDir = File(context.cacheDir, "patch-work"),
                                    target = target,
                                    onProgress = { step -> progress = step },
                                )
                            }.getOrElse { error ->
                                PatchReport(
                                    target = target,
                                    steps = listOf(
                                        dev.lunartear.host.patch.apk.PatchStep(
                                            "patch",
                                            false,
                                            error.message ?: error.toString(),
                                        ),
                                    ),
                                    outputApk = null,
                                    outputBytes = 0,
                                    sourceBytes = apk.length(),
                                )
                            }
                        }
                        report = result
                        LunarHost.log.append("patch", result.summary())
                        busy = false
                        progress = ""
                    }
                },
            ) { Text(if (busy) "working…" else "Patch APK") }
            if (progress.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Mono(progress)
            }
            if (busy) {
                Text(
                    "This reads and rewrites a 263 MB archive; expect a few minutes and ~800 MB of " +
                        "free space in app storage.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        report?.let { result ->
            SectionCard(if (result.ok) "4. Result" else "4. Result (failed)") {
                result.steps.forEach { step ->
                    Text(
                        "${if (step.ok) "OK  " else "FAIL"}  ${step.name}: ${step.detail}",
                        fontSize = 11.sp,
                        color = if (step.ok) OkGreen else MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Line("output", result.outputApk?.let { "${it.name} (${result.outputBytes / 1024 / 1024} MB)" } ?: "—")
                if (result.ok && result.outputApk != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Installing this replaces the game: the signature differs from the original, so " +
                            "Android will require uninstalling the existing app first. The app does not " +
                            "uninstall anything for you.",
                        fontSize = 11.sp,
                        color = WarnAmber,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { installApk(context, result.outputApk) }) { Text("Install") }
                        OutlinedButton(onClick = { shareApk(context, result.outputApk) }) { Text("Share") }
                    }
                }
            }
        }

        MasterDataSection()
    }
}

/**
 * Extends the master data in the asset tree to 2030 by running the bundled
 * `liblt-patch-masterdata.so` (the Go port of patch_masterdata.py).
 *
 * The original is kept as `<name>.orig` the first time, the patch is written to a
 * temporary file and only swapped in when it succeeds, and a running game server
 * is asked to re-read it through its admin webhook so no restart is needed.
 */
@Composable
private fun MasterDataSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by LunarHost.config.collectAsState()
    val state by LunarHost.supervisor.state.collectAsState()

    val masterData = remember(config.assetRoot) {
        config.assetRoot?.let { File(it, "assets/release/${AssetLayout.MASTER_DATA_NAME}") }
    }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reloaded by remember { mutableStateOf<Boolean?>(null) }

    SectionCard("5. Master data") {
        Text(
            "Extends time-gated content (EndDatetime columns, campaigns, labyrinth seasons) " +
                "to 2030. Same result as patch_masterdata.py: verified table-for-table against it.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(4.dp))
        Line("file", masterData?.absolutePath ?: "no asset folder selected")
        Line("size", masterData?.takeIf { it.isFile }?.let { "${it.length() / 1024} KB" } ?: "—")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && masterData?.isFile == true,
                onClick = {
                    val target = masterData ?: return@Button
                    busy = true
                    reloaded = null
                    scope.launch {
                        output = withContext(Dispatchers.IO) {
                            MasterDataTool.patch(context, target)
                        }
                        output = output.also { LunarHost.log.append("masterdata", it) }
                        busy = false
                    }
                },
            ) { Text(if (busy) "patching…" else "Extend to 2030") }

            OutlinedButton(
                enabled = state.phase == Phase.RUNNING && !busy,
                onClick = {
                    scope.launch {
                        reloaded = withContext(Dispatchers.IO) {
                            LunarHost.supervisor.reloadMasterData(config)
                        }
                    }
                },
            ) { Text("Reload server") }
        }
        reloaded?.let {
            Text(
                if (it) "server reloaded the new master data" else "reload failed (see Logs)",
                fontSize = 11.sp,
                color = if (it) OkGreen else MaterialTheme.colorScheme.error,
            )
        }
        if (output.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(output.takeLast(1500), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun installApk(context: android.content.Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { LunarHost.log.append("patch", "install intent failed: ${it.message}") }
}

private fun shareApk(context: android.content.Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share patched APK")) }
}
