package dev.lunartear.host.patch.apk

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBuffer
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the whole DEX stage against the **real** 3.7.1 APK: baksmali the Facebook
 * classes, apply the reference patcher's edits, reassemble and splice them back
 * into the original file.
 *
 * The APK is not committed; skip when it is absent:
 *
 *   $env:LUNAR_APK='F:\bak\lunar-tear-assets\nier-3.7.1.apk'
 *   powershell -ExecutionPolicy Bypass -File tools\gradle.ps1 :app:testDebugUnitTest
 *
 * The assertions on the string-data section and on dexdump exist because ART
 * rejected earlier hand-rolled versions of this stage on device ("Bad checksum",
 * then "String longer than indicated size 0"), so the header and the layout are
 * now checked by an independent tool as well as in-process.
 */
class DexPatcherTest {

    private fun patchAll(work: File): Pair<Map<String, ByteArray>, DexSurgeryResult> {
        val patched = LinkedHashMap<String, ByteArray>()
        val reports = mutableListOf<DexReport>()
        for (name in SmaliFixtures.dexEntryNames()) {
            val data = SmaliFixtures.dexBytes(name)
            val (out, report) = DexPatcher.patch(data, name, SmaliFixtures.AUTH_HOST, work)
            reports += report
            if (out != null) patched[name] = out
        }
        return patched to DexPatcher.summarize(reports)
    }

    @Test
    fun `the real client dex takes the facebook surgery`() {
        SmaliFixtures.requireApk()
        val work = SmaliFixtures.workDir("dex-patch")
        val (patched, surgery) = patchAll(work)
        println(surgery.detail())

        assertTrue("the surgery gate failed: ${surgery.detail()}", surgery.ok)
        assertEquals("no dex was rewritten", true, surgery.classesRewritten > 0)
        assertTrue(
            "nothing in any dex loads the auth host",
            patched.values.any { out -> DexPatcher.referencedStrings(out).any { it.contains(SmaliFixtures.AUTH_HOST) } },
        )

        for (name in SmaliFixtures.dexEntryNames()) {
            val original = SmaliFixtures.dexBytes(name)
            val out = patched[name]
            if (out == null || out === original) {
                assertSame("$name should have been returned untouched", original, patched[name])
                continue
            }
            assertTrue("$name is no longer a DEX", DexPatcher.isDex(out))
            assertEquals(
                "$name: classes were dropped by the rewrite",
                DexPatcher.classNames(original).sorted(),
                DexPatcher.classNames(out).sorted(),
            )
            val (checksumOk, signatureOk) = DexPatcher.verify(out)
            assertTrue("$name Adler-32 not written correctly", checksumOk)
            assertTrue("$name SHA-1 not written correctly", signatureOk)
            assertStringSectionIsPacked(out, "$name (patched)")
            assertNoDuplicateStrings(out, "$name (patched)")
        }
    }

    @Test
    fun `the patched dex carries the four structural edits`() {
        SmaliFixtures.requireApk()
        val work = SmaliFixtures.workDir("dex-verify")
        val (patched, _) = patchAll(work)
        val classes = patched["classes.dex"]
        assertNotNull("classes.dex was not patched", classes)

        // Disassemble the *output* - not the intermediate tree - so this validates
        // the splice, the reassembly and the pool writer together.
        val tree = SmaliFixtures.disassembleTree(
            classes!!,
            File(work, "verify"),
            listOf(
                "Lcom/facebook/internal/CustomTabUtils;",
                "Lcom/facebook/login/LoginBehavior;",
                "Lcom/facebook/internal/CustomTab;",
                "Lcom/facebook/internal/WebDialog\$DialogWebViewClient;",
            ),
        )
        val smaliRoot = SmaliFixtures.smaliRoot(tree)
        val utils = File(smaliRoot, SmaliPatches.CUSTOM_TAB_UTILS).readText()
        assertTrue(utils.contains("getDefaultRedirectURI()Ljava/lang/String;"))
        // The auth host lands in the classes that hold the SDK's domain constants
        // (FacebookSdk, ServerProtocol), so check the pool rather than one file.
        assertTrue(
            "the auth host is not in the patched pool",
            DexPatcher.stringValues(classes).contains(SmaliFixtures.AUTH_HOST),
        )
        assertTrue(File(smaliRoot, SmaliPatches.LOGIN_BEHAVIOR).readText().contains("const/4 v4, 0x0"))
        assertTrue(File(smaliRoot, SmaliPatches.CUSTOM_TAB).readText().contains("android.intent.action.VIEW"))
        assertTrue(
            File(smaliRoot, SmaliPatches.WEB_DIALOG_CLIENT).readText()
                .contains(SmaliPatchData.WEBDIALOG_SIGNATURE),
        )
    }

    /** Google's own tool is the independent check: it verifies the header and the pool. */
    @Test
    fun `google's dexdump accepts the patched dex`() {
        SmaliFixtures.requireApk()
        val dexdump = SmaliFixtures.requireDexdump()
        val work = SmaliFixtures.workDir("dex-dexdump")
        val (patched, _) = patchAll(work)

        assertDexdumpAccepts(dexdump, SmaliFixtures.dexBytes("classes.dex"), "the unmodified classes.dex")
        val out = patched["classes.dex"]
        assertNotNull("classes.dex was not patched", out)
        assertDexdumpAccepts(dexdump, out!!, "the patched classes.dex")
    }

    /** Patching a patched APK has to fail loudly rather than produce a half-patched client. */
    @Test
    fun `an already patched dex is rejected with an explanation`() {
        SmaliFixtures.requireApk()
        val work = SmaliFixtures.workDir("dex-twice")
        val (patched, _) = patchAll(work)
        val once = patched["classes.dex"] ?: return

        val (twice, report) = DexPatcher.patch(once, "classes.dex", SmaliFixtures.AUTH_HOST, work)
        assertEquals("a second patch should not produce a file", null, twice)
        assertTrue(
            "the failure does not mention that the APK is already patched: ${report.failures}",
            report.failures.any { it.contains("already") },
        )
    }

    @Test
    fun `a dex with no facebook classes is returned untouched`() {
        SmaliFixtures.requireApk()
        val work = SmaliFixtures.workDir("dex-none")
        val data = SmaliFixtures.dexBytes("classes3.dex")
        val (out, report) = DexPatcher.patch(data, "classes3.dex", SmaliFixtures.AUTH_HOST, work)
        assertSame("an untouched dex must not be re-written", data, out)
        assertEquals(0, report.facebookClasses)
    }

    @Test
    fun `a file that is not a dex is rejected`() {
        val notDex = ByteArray(200) { 0x41 }
        assertTrue(!DexPatcher.isDex(notDex))
        val failed = runCatching {
            DexPatcher.patch(notDex, "fake.dex", SmaliFixtures.AUTH_HOST, SmaliFixtures.workDir("dex-bogus"))
        }.exceptionOrNull()
        assertTrue("expected a rejection, got $failed", failed is IllegalArgumentException)
    }

    /** The verifier walks the string-data section sequentially: items must tile it. */
    private fun assertStringSectionIsPacked(data: ByteArray, label: String) {
        val offsets = stringItemOffsets(data)
        var expected = offsets.first()
        offsets.forEach { off ->
            assertEquals("$label: gap before the string item at 0x${"%X".format(off)}", expected, off)
            var p = off
            while ((data[p].toInt() and 0x80) != 0) p++   // uleb128 utf16 length
            p++
            while (data[p] != 0.toByte()) p++             // MUTF-8 bytes
            expected = p + 1                              // past the NUL
        }
    }

    /** A DEX must not carry two ids with the same value. */
    private fun assertNoDuplicateStrings(data: ByteArray, label: String) {
        val values = HashSet<String>(data.size / 16)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getInt(0x38)
        val idsOff = buffer.getInt(0x3C)
        for (i in 0 until count) {
            var p = buffer.getInt(idsOff + i * 4)
            while ((data[p].toInt() and 0x80) != 0) p++
            p++
            val start = p
            while (data[p] != 0.toByte()) p++
            val text = String(data, start, p - start, Charsets.UTF_8)
            assertTrue("$label: duplicate string value '$text'", values.add(text))
        }
    }

    private fun stringItemOffsets(data: ByteArray): List<Int> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.getInt(0x38)
        val idsOff = buffer.getInt(0x3C)
        return (0 until count).map { buffer.getInt(idsOff + it * 4) }.distinct().sorted()
    }

    private fun assertDexdumpAccepts(dexdump: File, bytes: ByteArray, label: String) {
        val tmp = File.createTempFile("lt-patched", ".dex")
        try {
            tmp.writeBytes(bytes)
            val process = ProcessBuilder(dexdump.absolutePath, tmp.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val bad = output.lineSequence().firstOrNull {
                it.contains("Bad checksum") || it.contains("Bad signature") ||
                    it.contains("Failure to verify") || it.contains("longer than indicated")
            }
            assertTrue("dexdump rejected $label: ${bad ?: output.take(400)}", bad == null)
        } finally {
            tmp.delete()
        }
    }

    /** Every class type in the file; used to prove a splice drops nothing. */
    @Suppress("unused")
    private fun classTypes(data: ByteArray): List<String> =
        DexBackedDexFile(Opcodes.getDefault(), DexBuffer(data)).classes.map { it.type }

    /** Class types whose code loads [literal], used by the scoping assertion. */
    @Suppress("unused")
    private fun referencing(data: ByteArray, literal: String): List<String> {
        val dexFile = DexBackedDexFile(Opcodes.getDefault(), DexBuffer(data))
        return dexFile.classes.filter { classDef ->
            classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.referenceType == ReferenceType.STRING &&
                        (instruction.reference as? StringReference)?.string == literal
                } == true
            }
        }.map { it.type }
    }

    @Test
    fun `the fixtures find the client dex`() {
        assumeTrue("no game APK configured", SmaliFixtures.apk.isFile)
        assertTrue(SmaliFixtures.dexEntryNames().contains("classes.dex"))
    }
}
