package dev.lunartear.host.patch.masterdata

import android.content.Context
import dev.lunartear.host.core.LunarHost
import dev.lunartear.host.core.NativeExec
import dev.lunartear.host.core.ServerRole
import java.io.File

/**
 * Runs the bundled master-data patcher against the master data in the asset tree.
 *
 * The tool is the Go port of `patch_masterdata.py`; its output is verified
 * table-for-table against the Python reference (see
 * `native/internal/ltmd/differential_test.go`). It is invoked as a child process
 * because it is a separate Go binary, so it must be exec'd from
 * `nativeLibraryDir` like the servers.
 *
 * Safety properties, in order:
 *  - the original is copied to `<name>.orig` once, and never overwritten;
 *  - the tool writes to `<name>.patched`, so a failure leaves the live file
 *    untouched;
 *  - the patched file replaces the original only after the tool exits 0.
 */
object MasterDataTool {

    fun backupFile(target: File): File = File(target.parentFile, target.name + ".orig")

    /** Patches [target] in place. Returns a human-readable report; never throws. */
    fun patch(context: Context, target: File): String {
        val binary = File(context.applicationInfo.nativeLibraryDir, ServerRole.MASTERDATA.fileName)
        if (!binary.isFile) return "FAILED: ${binary.name} is not bundled"
        if (!target.isFile) return "FAILED: no master data at ${target.absolutePath}"

        return runCatching {
            val backup = backupFile(target)
            if (!backup.isFile) target.copyTo(backup, overwrite = false)
            val temp = File(target.parentFile, target.name + ".patched")
            temp.delete()

            val result = NativeExec.run(
                listOf(binary.absolutePath, "--input", backup.absolutePath, "--output", temp.absolutePath),
                timeoutMs = 300_000,
            )
            if (result.timedOut) {
                temp.delete()
                return@runCatching "FAILED: timed out after 300s\n${result.output.takeLast(1500)}"
            }
            if (result.exitCode != 0 || !temp.isFile) {
                temp.delete()
                return@runCatching "FAILED (exit=${result.exitCode})\n${result.output.takeLast(1500)}"
            }

            temp.copyTo(target, overwrite = true)
            temp.delete()
            LunarHost.log.append("masterdata", "patched ${target.name} (${target.length()} bytes)")
            "OK — original kept as ${backup.name}, new size ${target.length()} bytes\n" +
                result.output.lineSequence()
                    .filter { it.contains(':') || it.startsWith("Emptied") }
                    .toList()
                    .takeLast(12)
                    .joinToString("\n")
        }.getOrElse { "FAILED: ${it.message}" }
    }
}
