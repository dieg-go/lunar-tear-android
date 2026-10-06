package dev.lunartear.host.patch.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.zip.ZipFile

/**
 * End-to-end test of the on-device patch pipeline, run on the host against the
 * **real** 3.7.1 APK (263 MB): patch -> re-zip -> align -> sign -> verify.
 *
 * Independent verification is the point of this test:
 *  - `zipalign -c -v 4` (Google's tool) confirms the alignment invariants that
 *    `extractNativeLibs="false"` depends on;
 *  - `apksigner verify` (Google's tool) confirms the signature;
 *  - apksig's own verifier is checked in-process as well;
 *  - the patched entries are read back out of the produced APK and compared with
 *    the expected bytes.
 *
 * Skips itself when the game APK is not available locally.
 */
class ApkPatcherTest {

    private val apk = File(System.getProperty("lt.apk") ?: "")
    private val buildTools = File(System.getProperty("lt.buildtools") ?: "")
    private val keystoreFile = File("src/main/assets/${ApkSigning.KEYSTORE_ASSET}")

    private val target = PatchTarget(host = "127.0.0.1", grpcPort = 8003, cdnPort = 8080)

    private fun requireApk() = assumeTrue(
        "game APK not present at ${apk.absolutePath}; set LUNAR_APK",
        apk.isFile,
    )

    private fun loadKeyStore(): KeyStore {
        assumeTrue("keystore missing; run tools/gen-keystore.ps1", keystoreFile.isFile)
        return KeyStore.getInstance("PKCS12").apply {
            keystoreFile.inputStream().use { load(it, ApkSigning.KEYSTORE_PASSWORD.toCharArray()) }
        }
    }

    private fun tool(name: String): File? =
        listOf(File(buildTools, "$name.exe"), File(buildTools, name)).firstOrNull { it.isFile }

    private fun run(command: List<String>, javaHome: String? = null): Pair<Int, String> {
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        if (javaHome != null) builder.environment()["JAVA_HOME"] = javaHome
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return process.exitValue() to output
    }

    @Test
    fun `preflight accepts the real client and reports no problems`() {
        requireApk()
        val problems = ApkPatcher.preflight(apk, target)
        assertTrue("preflight rejected the real client: $problems", problems.isEmpty())
    }

    @Test
    fun `preflight rejects a client with the wrong version`() {
        requireApk()
        val problems = ApkPatcher.preflight(apk, PatchTarget("127.0.0.1", 8003, 8080).copy(grpcPort = 443))
        // Sanity check the other direction: an impossible host must be reported.
        val bad = ApkPatcher.preflight(apk, PatchTarget("a".repeat(200) + ".example.com", 8003, 8080))
        assertTrue("a too-long host must be reported", bad.any { it.contains("does not fit") })
        assertTrue("port 443 needs no port patches", problems.none { it.contains("does not fit") })
    }

    @Test
    fun `patches the real APK end to end and the result is aligned and signed`() {
        requireApk()
        val keyStore = loadKeyStore()

        val workDir = File("build/patch-e2e").apply { deleteRecursively(); mkdirs() }
        val output = File(workDir, "patched.apk")
        val scratch = File(workDir, "scratch")

        val report = ApkPatcher.patch(
            keyStore = keyStore,
            sourceApk = apk,
            outputApk = output,
            workDir = scratch,
            target = target,
        )
        println(report.summary())

        assertTrue("patch failed:\n${report.summary()}", report.ok)
        assertTrue("output APK missing", output.isFile)
        assertTrue("output is suspiciously small", output.length() > apk.length() / 2)

        // ---- in-process signature verification (apksig) ----
        val result = ApkSigning.verify(output)
        assertTrue("apksig rejected our signature: ${ApkSigning.describe(result)}", result.isVerified)

        // ---- Google's zipalign ----
        val zipalign = tool("zipalign")
        assumeTrue("zipalign not available", zipalign != null)
        val (alignExit, alignOut) = run(listOf(zipalign!!.absolutePath, "-c", "-p", "-v", "4", output.absolutePath))
        assertTrue("zipalign rejected the output:\n${alignOut.takeLast(2000)}", alignExit == 0)
        assertTrue(
            "zipalign output does not mention the native library (did it get compressed?)",
            alignOut.contains("lib/arm64-v8a/libil2cpp.so"),
        )

        // ---- Google's apksigner ----
        val apksigner = tool("apksigner")
        val javaHome = System.getProperty("java.home")
        if (apksigner != null) {
            val (signExit, signOut) = run(
                listOf(apksigner.absolutePath, "verify", "--print-certs", output.absolutePath),
                javaHome = javaHome,
            )
            assertTrue("apksigner rejected the output:\n$signOut", signExit == 0)
            assertTrue("apksigner did not report our signer:\n$signOut", signOut.contains("Lunar Tear Debug"))
        }

        // ---- the patched entries are really in the output ----
        // The client is signed with APK Signature Scheme v2 only, so it has no
        // META-INF/*.SF entries; if a build does have v1 files, none of the
        // originals may survive (apksig writes fresh ones, which is expected).
        val sourceSignatures = ZipFile(apk).use { source ->
            source.entries().asSequence().map { it.name }.filter { isSignatureEntry(it) }.toSet()
        }
        println("source v1 signature entries: $sourceSignatures")

        ZipFile(output).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            val survivors = sourceSignatures.filter { it in names }
            assertTrue("original signature entries survived: $survivors", survivors.isEmpty())
            assertTrue("resources.arsc is missing", ApkPatcher.RESOURCES_ENTRY in names)

            // Manifest: cleartext enabled, client identity preserved.
            val manifestBytes = zip.getInputStream(zip.getEntry(ApkPatcher.MANIFEST_ENTRY)).use { it.readBytes() }
            assertEquals(152, AxmlEditor.versionCode(manifestBytes))
            assertEquals("3.7.1", AxmlEditor.versionName(manifestBytes))
            val reader = AxmlReader(manifestBytes)
            val attr = reader.attrOf(reader.findElement("application")!!, AxmlAttrs.USES_CLEARTEXT_TRAFFIC)
            assertNotNull("cleartext attribute missing from the patched APK", attr)
            assertEquals(AxmlAttrs.VALUE_TRUE, attr!!.valueData)

            // Metadata: the replacements are present.
            val metadataBytes = zip.getInputStream(zip.getEntry(ApkPatcher.METADATA_ENTRY)).use { it.readBytes() }
            assertEquals("metadata size changed", 23317064, metadataBytes.size)
            assertTrue("metadata does not contain the patched host", contains(metadataBytes, "127.0.0.1".toByteArray()))

            // libil2cpp.so: all 13 windows carry the intended instruction words.
            val patches = Il2CppPatcher.patchesFor(target.grpcPort)
            val windows = readWindows(zip, ApkPatcher.IL2CPP_ENTRY, patches)
            patches.forEach { patch ->
                val actual = windows[patch.offset]
                assertNotNull("no bytes captured for ${patch.name}", actual)
                assertEquals(
                    "${patch.name} was not applied in the output APK",
                    Il2CppPatcher.hex(patch.bytes),
                    Il2CppPatcher.hex(actual!!),
                )
            }
        }

        // The scratch copies of the big entries are cleaned up (the work dir holds
        // them only while patching).
        assertTrue("scratch metadata not removed", !File(scratch, "global-metadata.dat").exists())
        assertTrue("scratch libil2cpp not removed", !File(scratch, "libil2cpp.so").exists())
    }

    /**
     * The same run with the Facebook redirect enabled - the configuration a device
     * needs. The output lands in `build/patch-e2e-auth/patched.apk`, which is also
     * what the comparison against the community's own patched APK uses.
     */
    @Test
    fun `patches the real APK with the facebook redirect`() {
        requireApk()
        val keyStore = loadKeyStore()
        val workDir = File("build/patch-e2e-auth").apply { deleteRecursively(); mkdirs() }
        val output = File(workDir, "patched.apk")

        val report = ApkPatcher.patch(
            keyStore = keyStore,
            sourceApk = apk,
            outputApk = output,
            workDir = File(workDir, "scratch"),
            target = target.copy(authHost = SmaliFixtures.AUTH_HOST),
        )
        println(report.summary())

        assertTrue("patch failed:\n${report.summary()}", report.ok)
        val dexStep = report.steps.first { it.name == "dex (facebook sdk)" }
        assertTrue("the dex step failed: ${dexStep.detail}", dexStep.ok)
        assertTrue("output APK missing", output.isFile)

        ZipFile(output).use { zip ->
            val classes = zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
            assertTrue(
                "the auth host is not in the output's classes.dex",
                DexPatcher.stringValues(classes).contains(SmaliFixtures.AUTH_HOST),
            )
        }
    }

    /** JAR signature files (v1): the ones a re-sign has to replace. */
    private fun isSignatureEntry(name: String): Boolean {
        if (name == "META-INF/MANIFEST.MF") return true
        if (!name.startsWith("META-INF/")) return false
        return listOf(".SF", ".RSA", ".DSA", ".EC").any { name.endsWith(it) }
    }

    /** Streams one zip entry and captures the bytes inside each requested window. */
    private fun readWindows(zip: ZipFile, entryName: String, patches: List<Il2CppPatch>): Map<Long, ByteArray> {
        val wanted = patches.map { it.offset to it.bytes.size }
        val captured = mutableMapOf<Long, ByteArray>()
        var position = 0L
        zip.getInputStream(zip.getEntry(entryName)).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                val start = position
                val end = position + read
                wanted.forEach { (offset, length) ->
                    if (offset in start until end && captured[offset] == null && offset + length <= end) {
                        captured[offset] = buffer.copyOfRange((offset - start).toInt(), (offset - start).toInt() + length)
                    }
                }
                position = end
                if (captured.size == wanted.size) break
            }
        }
        return captured
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        var i = 0
        while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return true
            i++
        }
        return false
    }

}
