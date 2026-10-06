package dev.lunartear.host.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** One required path in the asset tree, with the reason it matters. */
data class AssetCheck(
    val label: String,
    val path: String,
    val ok: Boolean,
    val detail: String,
)

/** Result of validating a candidate asset root. */
data class AssetReport(
    val root: String,
    val checks: List<AssetCheck>,
    /** "unified" (revisions/0/...), "android"/"ios" (split trees) or null when no tree is present. */
    val treeKind: String?,
    val revisionCount: Int,
    val revisionDirs: List<Int>,
    val warnings: List<String>,
    val hints: List<String>,
) {
    val usable: Boolean
        get() = treeKind != null && checks.filterNot { it.label.startsWith("tree:") }.all { it.ok }
}

/**
 * Validates the layout the upstream servers actually read.
 *
 * From the upstream sources:
 *  - `cmd/lunar-tear/main.go` reads `assets/release/20240404193219.bin.e`
 *    *relative to its working directory*, so the servers are launched with the
 *    asset root as cwd.
 *  - `internal/service/octo.go` serves the master data from
 *    `<assets-dir>/assets/release/20240404193219.bin.e` for
 *    `/assets/release/{version}/database.bin` requests.
 *  - `internal/service/listbin.go` prefers
 *    `assets/revisions/{rev}/{platform}/list.bin` and falls back to
 *    `assets/revisions/{rev}/list.bin` (the unified dump layout).
 *  - `cmd/wizard/main.go` requires one complete rev-0 tree.
 */
object AssetLayout {

    const val MASTER_DATA_NAME = "20240404193219.bin.e"

    private val treeCandidates = listOf(
        "unified" to "",
        "android" to "android",
        "ios" to "ios",
    )

    fun validate(root: File): AssetReport {
        val checks = mutableListOf<AssetCheck>()

        val master = File(root, "assets/release/$MASTER_DATA_NAME")
        checks += AssetCheck(
            label = "master data",
            path = master.path,
            ok = master.isFile,
            detail = if (master.isFile) "${master.length() / 1024} KB" else "missing",
        )

        var chosenKind: String? = null
        var chosenChecks: List<AssetCheck> = emptyList()
        for ((kind, sub) in treeCandidates) {
            val base = if (sub.isEmpty()) File(root, "assets/revisions/0") else File(root, "assets/revisions/0/$sub")
            val listBin = File(base, "list.bin")
            val bundles = File(base, "assetbundle")
            val resources = File(base, "resources")
            val candidate = listOf(
                AssetCheck("tree:$kind list.bin", listBin.path, listBin.isFile, if (listBin.isFile) "${listBin.length()} B" else "missing"),
                AssetCheck("tree:$kind assetbundle/", bundles.path, bundles.isDirectory, if (bundles.isDirectory) "present" else "missing"),
                AssetCheck("tree:$kind resources/", resources.path, resources.isDirectory, if (resources.isDirectory) "present" else "missing"),
            )
            if (candidate.any { it.ok } && chosenKind == null) {
                chosenKind = kind
                chosenChecks = candidate
            }
            if (candidate.all { it.ok }) {
                chosenKind = kind
                chosenChecks = candidate
                break
            }
        }
        checks += chosenChecks

        val revisionsDir = File(root, "assets/revisions")
        val revisionDirs = revisionsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { it.name.toIntOrNull() }
            ?.sorted()
            ?: emptyList()

        val warnings = mutableListOf<String>()
        val hints = mutableListOf<String>()

        if (treeCandidates.none { (kind, sub) ->
                val base = if (sub.isEmpty()) File(root, "assets/revisions/0") else File(root, "assets/revisions/0/$sub")
                File(base, "list.bin").isFile
            }
        ) {
            val dumpRevisions = File(root, "revisions")
            if (dumpRevisions.isDirectory) {
                hints += "This looks like an extracted dump root: it has revisions/ but no assets/. " +
                    "Rename revisions/ to assets/revisions/ (instant, no copy) to use it as is."
            }
        }
        if (revisionDirs.isNotEmpty() && revisionDirs.first() != 0) {
            warnings += "revision 0 is missing; the client always asks for it first"
        }
        if (revisionDirs.size > 1) {
            val missingList = revisionDirs.count { rev -> !File(revisionsDir, "$rev/list.bin").isFile }
            if (missingList > 0) warnings += "$missingList of ${revisionDirs.size} revision folders have no list.bin"
        }

        return AssetReport(
            root = root.path,
            checks = checks,
            treeKind = chosenKind,
            revisionCount = revisionDirs.size,
            revisionDirs = revisionDirs,
            warnings = warnings,
            hints = hints,
        )
    }

    /**
     * Renames `<root>/revisions` to `<root>/assets/revisions` in place.
     * Both directories are on the same volume so this is a metadata operation,
     * not a copy - the difference between seconds and hours for a 15 GB tree.
     */
    fun relocateDumpRoot(root: File): Result<File> = runCatching {
        val from = File(root, "revisions")
        val assetsDir = File(root, "assets")
        val to = File(assetsDir, "revisions")
        require(from.isDirectory) { "no revisions/ directory in ${root.path}" }
        require(!to.exists()) { "assets/revisions already exists" }
        assetsDir.mkdirs()
        require(from.renameTo(to)) { "rename failed (different volume or no permission?)" }
        File(assetsDir, "release").mkdirs()
        to
    }

    /**
     * Copies a user-supplied master data file into place under the canonical
     * name the server hardcodes. Verifies the size is plausible first so a
     * wrong pick (e.g. the APK) fails immediately.
     */
    fun installMasterData(root: File, source: File, expectedBytes: Long? = null): Result<File> = runCatching {
        require(source.isFile) { "not a file: ${source.path}" }
        if (expectedBytes != null) {
            require(source.length() == expectedBytes) {
                "size ${source.length()} does not match the expected $expectedBytes bytes"
            }
        }
        val release = File(root, "assets/release")
        release.mkdirs()
        val target = File(release, MASTER_DATA_NAME)
        if (source.canonicalPath != target.canonicalPath) {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target
    }
}

/** All-files access, needed because the servers are separate processes reading real paths. */
object StorageAccess {

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun allFilesAccessIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )
        } else {
            null
        }

    /** True when [path] is on shared storage (as opposed to app-private storage). */
    fun isSharedPath(path: String): Boolean =
        path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith("/mnt/")
}
