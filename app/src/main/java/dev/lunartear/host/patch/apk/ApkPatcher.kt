package dev.lunartear.host.patch.apk

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** One step of a patch run, with enough detail for the UI and the log. */
data class PatchStep(val name: String, val ok: Boolean, val detail: String)

data class PatchReport(
    val target: PatchTarget,
    val steps: List<PatchStep>,
    val outputApk: File?,
    val outputBytes: Long,
    val sourceBytes: Long,
) {
    val ok: Boolean get() = steps.all { it.ok } && outputApk != null
    val failures: List<PatchStep> get() = steps.filterNot { it.ok }

    fun summary(): String = buildString {
        append(if (ok) "patched OK" else "PATCH FAILED")
        append("  (${sourceBytes / 1024 / 1024} MB -> ${outputBytes / 1024 / 1024} MB)\n")
        append("  ${target.describe()}\n")
        steps.forEach { step ->
            append("  [")
            append(if (step.ok) "ok" else "FAIL")
            append("] ")
            append(step.name)
            if (step.detail.isNotBlank()) append(": ").append(step.detail)
            append('\n')
        }
    }
}

/**
 * Patches the game APK so it talks to a local lunar-tear server.
 *
 * The reference implementation (lunar-scripts/android/patch_apk.py) decompiles
 * with apktool, edits, rebuilds, aligns and signs - none of which can run on a
 * phone. Every edit it makes is a *byte-level* edit inside the APK, so this does
 * the same edits directly and rewrites the archive:
 *
 *   global-metadata.dat  string literals   -> server host and CDN URL
 *   lib/arm64-v8a/libil2cpp.so              -> SSL/encryption/IAP patches + gRPC port
 *   AndroidManifest.xml                     -> android:usesCleartextTraffic="true"
 *
 * The manifest is edited rather than extended with a new
 * `res/xml/network_security_config.xml` on purpose: a new resource *file* needs a
 * new resource id, which would mean writing `resources.arsc`. The cleartext
 * attribute is exactly equivalent for our traffic and needs no new resources.
 *
 * The 121 MB library is patched on disk, never in memory, and the APK is streamed
 * entry by entry.
 */
object ApkPatcher {

    const val EXPECTED_VERSION_CODE = 152
    const val EXPECTED_VERSION_NAME = "3.7.1"
    const val METADATA_ENTRY = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
    const val IL2CPP_ENTRY = "lib/arm64-v8a/libil2cpp.so"
    const val MANIFEST_ENTRY = "AndroidManifest.xml"
    const val RESOURCES_ENTRY = "resources.arsc"

    private val DROPPED_PREFIXES = listOf("META-INF/")
    private val DROPPED_SUFFIXES = listOf(".SF", ".RSA", ".DSA", ".EC")
    private const val DROPPED_MANIFEST = "META-INF/MANIFEST.MF"

    /**
     * Uncompressed native libraries must start on a page boundary when
     * `extractNativeLibs="false"`, otherwise the installer rejects the APK with
     * "Failed to extract native libraries". 4096 is what `zipalign -p` uses.
     */
    const val NATIVE_PAGE_ALIGNMENT = 4096

    /** Everything that can be checked by reading the source APK, without writing anything. */
    fun preflight(sourceApk: File, target: PatchTarget): List<String> {
        val problems = mutableListOf<String>()
        if (!sourceApk.isFile) {
            problems += "source APK not found: ${sourceApk.absolutePath}"
            return problems
        }
        problems += PatchRecipe.problems(target)

        runCatching {
            ZipFile(sourceApk).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                if (MANIFEST_ENTRY !in names) problems += "APK has no $MANIFEST_ENTRY"
                if (METADATA_ENTRY !in names) problems += "APK has no $METADATA_ENTRY (is this the right client?)"
                if (IL2CPP_ENTRY !in names) {
                    problems += "APK has no $IL2CPP_ENTRY (only the arm64-v8a 3.7.1 client is supported)"
                }
                val manifestEntry = zip.getEntry(MANIFEST_ENTRY)
                if (manifestEntry != null) {
                    val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                    val code = runCatching { AxmlEditor.versionCode(bytes) }.getOrNull()
                    val name = runCatching { AxmlEditor.versionName(bytes) }.getOrNull()
                    if (code != EXPECTED_VERSION_CODE || name != EXPECTED_VERSION_NAME) {
                        problems += "client is versionCode=$code versionName=$name; " +
                            "this patcher only supports $EXPECTED_VERSION_NAME ($EXPECTED_VERSION_CODE)"
                    }
                }
            }
        }.onFailure { problems += "cannot read the APK: ${it.message}" }

        return problems
    }

    /**
     * Runs the whole patch. [workDir] needs room for the extracted
     * `global-metadata.dat` (23 MB) and `libil2cpp.so` (121 MB), plus the output
     * APK alongside its input - roughly three times the APK size in free space.
     *
     * [keyStore] is passed in rather than loaded here so the whole pipeline is
     * testable off-device; the Android layer gets it from assets via
     * [ApkSigning.loadKeystore].
     */
    fun patch(
        keyStore: java.security.KeyStore,
        sourceApk: File,
        outputApk: File,
        workDir: File,
        target: PatchTarget,
        onProgress: (String) -> Unit = {},
    ): PatchReport {
        val steps = mutableListOf<PatchStep>()
        val sourceBytes = sourceApk.length()
        workDir.mkdirs()
        outputApk.parentFile?.mkdirs()

        val problems = preflight(sourceApk, target)
        if (problems.isNotEmpty()) {
            problems.forEach { steps += PatchStep("preflight", false, it) }
            return PatchReport(target, steps, null, 0, sourceBytes)
        }

        val metadataFile = File(workDir, "global-metadata.dat")
        val il2cppFile = File(workDir, "libil2cpp.so")
        var patchedManifest: ByteArray? = null
        val patchedDex = mutableMapOf<String, File>()

        ZipFile(sourceApk).use { zip ->
            // 1. global-metadata.dat -----------------------------------------
            onProgress("patching global-metadata.dat")
            val replacements = PatchRecipe.replacements(target)
            val metadataEntry = zip.getEntry(METADATA_ENTRY)!!
            zip.getInputStream(metadataEntry).use { input -> metadataFile.outputStream().use { input.copyTo(it) } }
            val metadataResult = MetadataPatcher.patchFileInPlace(metadataFile, replacements)
            // Only the rewrites marked required gate the patch: the Facebook
            // domain, for instance, is absent from some client builds and its
            // absence is not a reason to refuse an otherwise correct patch.
            val required = replacements.filter { it.required }
            val requiredApplied = required.count { r ->
                metadataResult.outcomes.any { it.first == r && it.second is StringOutcome.Applied }
            }
            val metadataOk = requiredApplied == required.size
            steps += PatchStep(
                "global-metadata.dat",
                metadataOk,
                "${metadataResult.applied}/${replacements.size} strings " +
                    "($requiredApplied/${required.size} required, ${metadataResult.stringLiteralEntries} literals)",
            )
            if (!metadataOk) {
                return PatchReport(target, steps, null, 0, sourceBytes)
            }

            // 2. libil2cpp.so --------------------------------------------------
            onProgress("extracting libil2cpp.so (121 MB)")
            val il2cppEntry = zip.getEntry(IL2CPP_ENTRY)!!
            zip.getInputStream(il2cppEntry).use { input -> il2cppFile.outputStream().use { input.copyTo(it) } }
            onProgress("patching libil2cpp.so")
            val il2cppResult = runCatching { Il2CppPatcher.patchFileInPlace(il2cppFile, target.grpcPort) }
            if (il2cppResult.isFailure) {
                steps += PatchStep("libil2cpp.so", false, il2cppResult.exceptionOrNull()?.message ?: "failed")
                return PatchReport(target, steps, null, 0, sourceBytes)
            }
            val soResult = il2cppResult.getOrThrow()
            steps += PatchStep(
                "libil2cpp.so",
                soResult.mismatches.isEmpty(),
                "${soResult.applied} applied, ${soResult.alreadyApplied} already present, " +
                    "${Il2CppPatcher.patchesFor(target.grpcPort).size} sites",
            )
            if (soResult.mismatches.isNotEmpty()) {
                return PatchReport(target, steps, null, 0, sourceBytes)
            }

            // 3. AndroidManifest.xml -------------------------------------------
            onProgress("adding the cleartext permission")
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY)!!
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            patchedManifest = runCatching { AxmlEditor.enableCleartextTraffic(manifestBytes) }.getOrElse {
                steps += PatchStep("AndroidManifest.xml", false, it.message ?: "failed")
                return PatchReport(target, steps, null, 0, sourceBytes)
            }
            steps += PatchStep(
                "AndroidManifest.xml",
                true,
                "usesCleartextTraffic=true added (${manifestBytes.size} -> ${patchedManifest!!.size} bytes)",
            )

            // 3b. Facebook SDK smali surgery inside classes*.dex ---------------
            // The metadata patcher only reaches the Unity/C# half of the Facebook
            // SDK; the Java half keeps its URLs in the DEX string pools, and its
            // login flow has to be forced onto the in-app WebView path. See
            // DexPatcher - the string pass alone is not enough to log in.
            if (target.authHost != null) {
                onProgress("redirecting the Facebook SDK (smali)")
                val reports = mutableListOf<DexReport>()
                zip.entries().asSequence()
                    .filter { !it.isDirectory && DexPatcher.isDexEntry(it.name) }
                    .forEach { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val (rewritten, report) = DexPatcher.patch(bytes, entry.name, target.authHost, workDir)
                        if (rewritten != null && report.classesRewritten > 0) {
                            // Written out rather than kept in memory: a phone heap
                            // cannot hold several rewritten DEX files at once.
                            val out = File(workDir, "patched-${entry.name}")
                            out.writeBytes(rewritten)
                            patchedDex[entry.name] = out
                        }
                        reports += report
                    }
                val surgery = DexPatcher.summarize(reports)
                steps += PatchStep("dex (facebook sdk)", surgery.ok, surgery.detail())
                if (!surgery.ok) {
                    return PatchReport(target, steps, null, 0, sourceBytes)
                }
            }

            // 4. Rewrite the archive -------------------------------------------
            onProgress("writing the patched APK")
            val unsigned = File(workDir, "patched-unsigned.apk")
            ApkZipWriter(unsigned).use { writer ->
                zip.entries().asSequence().forEach { entry ->
                    if (shouldDrop(entry)) return@forEach
                    val name = entry.name
                    when (name) {
                        METADATA_ENTRY -> writer.add(name, ApkZipWriter.DEFLATED, { metadataFile.inputStream() })
                        IL2CPP_ENTRY -> writer.add(
                            name,
                            ApkZipWriter.STORED,
                            { il2cppFile.inputStream() },
                            il2cppFile.length(),
                            alignment = NATIVE_PAGE_ALIGNMENT,
                        )

                        MANIFEST_ENTRY -> {
                            val bytes = patchedManifest!!
                            writer.add(name, ApkZipWriter.DEFLATED, { bytes.inputStream() }, bytes.size.toLong())
                        }

                        else -> {
                            val patchedDexFile = patchedDex[name]
                            if (patchedDexFile != null) {
                                writer.add(
                                    name,
                                    if (entry.method == ZipEntry.STORED) ApkZipWriter.STORED else ApkZipWriter.DEFLATED,
                                    { patchedDexFile.inputStream() },
                                    patchedDexFile.length(),
                                    alignment = 4,
                                )
                                return@forEach
                            }
                            // Keep the platform's packaging invariants: native
                            // libraries uncompressed and *page* aligned, the resource
                            // table uncompressed and 4-byte aligned.
                            val isNative = name.startsWith("lib/") && name.endsWith(".so")
                            val mustBeStored = isNative || name == RESOURCES_ENTRY ||
                                entry.method == ZipEntry.STORED
                            writer.add(
                                name,
                                if (mustBeStored) ApkZipWriter.STORED else ApkZipWriter.DEFLATED,
                                { zip.getInputStream(entry) },
                                entry.size,
                                alignment = if (isNative) NATIVE_PAGE_ALIGNMENT else 4,
                            )
                        }
                    }
                }
                writer.finish()
            }
            steps += PatchStep("archive", true, "${unsigned.length() / 1024 / 1024} MB re-zipped")

            // The installer refuses an APK whose uncompressed native libraries are
            // not page aligned when extractNativeLibs="false", with a message
            // ("Failed to extract native libraries") that says nothing about
            // alignment. Check it here, before signing.
            val misaligned = ApkZipWriter.findMisalignedStoredEntries(unsigned, NATIVE_PAGE_ALIGNMENT)
            steps += PatchStep(
                "alignment",
                misaligned.isEmpty(),
                if (misaligned.isEmpty()) {
                    "all uncompressed entries page-aligned to $NATIVE_PAGE_ALIGNMENT"
                } else {
                    "${misaligned.size} misaligned: " + misaligned.take(4).joinToString("; ")
                },
            )
            if (misaligned.isNotEmpty()) {
                return PatchReport(target, steps, null, 0, sourceBytes)
            }
            zip.close()
            metadataFile.delete()
            il2cppFile.delete()
            patchedDex.values.forEach { it.delete() }

            // 5. Sign ----------------------------------------------------------
            onProgress("signing")
            val identity = ApkSigning.identity(keyStore)
            runCatching { ApkSigning.sign(keyStore, unsigned, outputApk) }.onFailure {
                steps += PatchStep("signing", false, it.message ?: "failed")
                return PatchReport(target, steps, null, 0, sourceBytes)
            }
            steps += PatchStep("signing", true, "${identity.alias} (${identity.subject})")

            // 6. Verify --------------------------------------------------------
            onProgress("verifying")
            val verification = runCatching { ApkSigning.verify(outputApk) }.getOrElse {
                steps += PatchStep("verify", false, it.message ?: "failed")
                return PatchReport(target, steps, outputApk, outputApk.length(), sourceBytes)
            }
            steps += PatchStep("verify", verification.isVerified, ApkSigning.describe(verification))

            // The artifact we hand over must still satisfy the packaging invariant
            // after signing: this is the difference between an APK that installs
            // and "Failed to extract native libraries".
            val finalMisaligned = ApkZipWriter.findMisalignedStoredEntries(outputApk, NATIVE_PAGE_ALIGNMENT)
            steps += PatchStep(
                "final alignment",
                finalMisaligned.isEmpty(),
                if (finalMisaligned.isEmpty()) {
                    "signed APK keeps every native library page-aligned"
                } else {
                    "${finalMisaligned.size} misaligned after signing: " +
                        finalMisaligned.take(3).joinToString("; ")
                },
            )
            if (finalMisaligned.isNotEmpty()) {
                return PatchReport(target, steps, null, 0, sourceBytes)
            }
            unsigned.delete()
        }

        return PatchReport(target, steps, outputApk, outputApk.length(), sourceBytes)
    }

    /** Old signature files must go, or the new signature is rejected as inconsistent. */
    private fun shouldDrop(entry: ZipEntry): Boolean {
        val name = entry.name
        if (name == DROPPED_MANIFEST) return true
        if (DROPPED_PREFIXES.any { name.startsWith(it) } && DROPPED_SUFFIXES.any { name.endsWith(it) }) return true
        return false
    }
}
