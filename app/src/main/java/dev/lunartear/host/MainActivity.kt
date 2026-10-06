package dev.lunartear.host

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.lunartear.host.core.AssetLayout
import dev.lunartear.host.core.HostMode
import dev.lunartear.host.core.Phase
import dev.lunartear.host.patch.masterdata.MasterDataTool
import dev.lunartear.host.core.LunarHost
import dev.lunartear.host.patch.apk.ApkPatcher
import dev.lunartear.host.patch.apk.ApkSigning
import dev.lunartear.host.patch.apk.PatchTarget
import dev.lunartear.host.service.ServerService
import dev.lunartear.host.ui.LunarTearApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LunarHost.initialize(this)
        applyDebugExtras()
        setContent { LunarTearApp() }
    }

    /**
     * Debug builds accept configuration through intent extras, which is what
     * tools/device-smoke.ps1 uses to drive the app over adb without tapping the
     * screen (a locked device cannot be scripted through the UI):
     *
     *   adb shell am start -n <pkg>/.MainActivity \
     *       --es assetRoot /sdcard/lunar-tear \
     *       --es sourceApk /sdcard/Download/client.apk \
     *       --ez autoStart true --ez patchApk true
     *
     * Startup and patching happen here, inside the app's own uid, rather than
     * through `am start-foreground-service`: ServerService is (correctly) not
     * exported, so adb shell cannot start it.
     */
    private fun applyDebugExtras() {
        if (!BuildConfig.DEBUG) return
        val assetRoot = intent?.getStringExtra("assetRoot")
        val sourceApk = intent?.getStringExtra("sourceApk")
        val authPort = intent?.getIntExtra("authPort", -1) ?: -1
        if (assetRoot != null || sourceApk != null || authPort >= 0) {
            val hostMode = intent.getStringExtra("hostMode")?.let {
                runCatching { HostMode.valueOf(it.uppercase()) }.getOrNull()
            }
            LunarHost.updateConfig {
                it.copy(
                    assetRoot = assetRoot ?: it.assetRoot,
                    sourceApk = sourceApk ?: it.sourceApk,
                    authPort = if (authPort >= 0) authPort else it.authPort,
                    hostMode = hostMode ?: it.hostMode,
                    grpcPort = intent.getIntExtra("grpcPort", it.grpcPort),
                    cdnPort = intent.getIntExtra("cdnPort", it.cdnPort),
                )
            }
            LunarHost.log.append(
                "ui",
                "debug intent applied: assetRoot=$assetRoot sourceApk=$sourceApk authPort=$authPort",
            )
        }
        if (intent?.getBooleanExtra("autoStart", false) == true) {
            LunarHost.log.append("ui", "debug intent: starting servers")
            ServerService.start(this)
        }
        if (intent?.getBooleanExtra("patchApk", false) == true) {
            runPatchForTesting()
        }
        if (intent?.getBooleanExtra("patchMasterData", false) == true) {
            val assetRoot = LunarHost.config.value.assetRoot
            if (assetRoot == null) {
                LunarHost.log.append("masterdata", "debug patch: no asset root configured")
            } else {
                lifecycleScope.launch(Dispatchers.IO) {
                    val target = File(assetRoot, "assets/release/${AssetLayout.MASTER_DATA_NAME}")
                    val report = MasterDataTool.patch(this@MainActivity, target)
                    LunarHost.log.append("masterdata", report)
                    // This runs from the same intent that starts the servers, and patching
                    // an already-patched file takes about a second - so without waiting, the
                    // reload is skipped and device-smoke.ps1's "server reloaded the new
                    // master data" check fails without anything being wrong.
                    awaitStackSettled()
                    if (LunarHost.supervisor.state.value.phase == Phase.RUNNING) {
                        LunarHost.supervisor.reloadMasterData(LunarHost.config.value)
                    }
                }
            }
        }
    }

    /** Waits (bounded) until the stack is running, or clearly will not be. */
    private suspend fun awaitStackSettled(timeoutMs: Long = 180_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (LunarHost.supervisor.state.value.phase) {
                Phase.RUNNING, Phase.FAILED, Phase.STOPPED -> return
                else -> delay(500)
            }
        }
    }

    /**
     * Debug-only: runs the APK patch exactly as the Patch tab does and logs the
     * report to the session log, so a device run can be verified without taps.
     */
    private fun runPatchForTesting() {
        val apkPath = LunarHost.config.value.sourceApk
        if (apkPath == null) {
            LunarHost.log.append("patch", "debug patch: no source APK configured")
            return
        }
        val target = PatchTarget(
            host = LunarHost.supervisor.resolveHost(LunarHost.config.value),
            grpcPort = LunarHost.config.value.grpcPort,
            cdnPort = LunarHost.config.value.cdnPort,
            authHost = LunarHost.config.value.patchAuthHost,
        )
        LunarHost.log.append("patch", "debug patch: ${File(apkPath).name} -> $target")
        lifecycleScope.launch(Dispatchers.IO) {
            val keyStore = runCatching { ApkSigning.loadKeystore(this@MainActivity) }.getOrElse {
                LunarHost.log.append("patch", "keystore unavailable: ${it.message}")
                return@launch
            }
            val outDir = File(filesDir, "patched").apply { mkdirs() }
            val report = runCatching {
                ApkPatcher.patch(
                    keyStore = keyStore,
                    sourceApk = File(apkPath),
                    outputApk = File(outDir, "nier-reincarnation-3.7.1-lunartear.apk"),
                    workDir = File(cacheDir, "patch-work"),
                    target = target,
                    onProgress = { LunarHost.log.append("patch", "… $it") },
                )
            }.getOrElse { error ->
                LunarHost.log.append("patch", "patch threw: ${error.message}")
                return@launch
            }
            LunarHost.log.append("patch", report.summary())
        }
    }
}
