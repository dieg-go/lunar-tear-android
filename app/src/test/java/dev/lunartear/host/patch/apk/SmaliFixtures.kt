package dev.lunartear.host.patch.apk

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.zip.ZipFile

/**
 * Shared setup for the tests that run the Facebook-SDK surgery against the real
 * 3.7.1 APK, and against the reference patcher it is ported from.
 *
 * The APK is not committed; every test that needs it skips itself when it is
 * missing (`LUNAR_APK`), like the rest of the patcher suite.
 */
internal object SmaliFixtures {

    val apk: File = File(System.getProperty("lt.apk") ?: "")

    /** The community's own configuration, i.e. what the colab notebook patches in. */
    const val AUTH_HOST = "127.0.0.1:3000"

    val referenceRepo: File = File(System.getenv("LUNAR_SCRIPTS") ?: "C:\\Users\\diego\\lunar-scripts")

    val python: File = File(
        System.getenv("LUNAR_PYTHON")
            ?: "C:\\Users\\diego\\AppData\\Local\\Programs\\Python\\Python312\\python.exe",
    )

    fun requireApk() = assumeTrue(
        "game APK not present at ${apk.absolutePath}; set LUNAR_APK",
        apk.isFile,
    )

    fun dexEntryNames(): List<String> = ZipFile(apk).use { zip ->
        zip.entries().toList().map { it.name }.filter { DexPatcher.isDexEntry(it) }.sorted()
    }

    fun dexBytes(name: String): ByteArray = ZipFile(apk).use { zip ->
        val entry = zip.getEntry(name) ?: error("$name is not in ${apk.name}")
        zip.getInputStream(entry).readBytes()
    }

    /** A fresh working directory under the module's build dir. */
    fun workDir(name: String): File =
        File("build/tmp/$name").apply { deleteRecursively(); mkdirs() }

    /**
     * Disassembles the requested classes (all targets by default) into
     * `<dir>/smali` and returns **`<dir>`** - the tree root, i.e. what the
     * reference patcher calls `apk_dir` and expects a `smali/` inside.
     */
    fun disassembleTree(
        data: ByteArray,
        dir: File,
        classTypes: List<String> = DexPatcher.targetClassTypes(data),
    ): File {
        val smaliRoot = smaliRoot(dir)
        smaliRoot.deleteRecursively()
        smaliRoot.mkdirs()
        check(DexPatcher.disassemble(data, classTypes, smaliRoot)) {
            "baksmali failed for ${classTypes.size} classes"
        }
        return dir
    }

    /** The `smali/` directory inside a tree root. */
    fun smaliRoot(tree: File): File = File(tree, "smali")

    fun smaliFile(smaliRoot: File, relative: String): File = File(smaliRoot, relative)

    /** Google's own dex validator, when the build tools are cached. */
    fun dexdump(): File? {
        val buildTools = File(System.getProperty("lt.buildtools") ?: "")
        return listOf(File(buildTools, "dexdump.exe"), File(buildTools, "dexdump")).firstOrNull { it.isFile }
    }

    fun requireDexdump(): File {
        val dexdump = dexdump()
        assumeTrue("dexdump not present in the cached build tools", dexdump != null)
        return dexdump!!
    }

    fun requireReference(): Pair<File, File> {
        assumeTrue(
            "reference patcher not present at ${referenceRepo.absolutePath}",
            File(referenceRepo, "android/patch_apk.py").isFile,
        )
        assumeTrue("python not present at ${python.absolutePath}", python.isFile)
        return referenceRepo to python
    }

    fun repoRoot(): File = File(System.getProperty("lt.reporoot") ?: "..")

    /** Runs a command, failing the test with its output when it exits non-zero. */
    fun run(command: List<String>, workDir: File? = null): String {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        if (workDir != null) builder.directory(workDir)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "${command.joinToString(" ")} exited $code:\n$output" }
        return output
    }

    /** Snapshot of a smali tree, for "a second pass changes nothing" assertions. */
    fun snapshot(smaliRoot: File): Map<String, String> =
        smaliRoot.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(smaliRoot).path.replace(File.separatorChar, '/') to it.readText() }
}
