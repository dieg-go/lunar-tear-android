package dev.lunartear.host.patch.apk

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBuffer
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Facebook-SDK surgery is a port of `lunar-scripts/android/patch_apk.py`, and
 * these tests hold it to that:
 *
 *  - `the port produces the same smali as the reference patcher` disassembles the
 *    client's Facebook classes twice, applies the edits with [SmaliPatches] to one
 *    tree and with the reference itself (via tools/reference-smali-patch.py) to the
 *    other, and requires the two trees to be identical file for file. That is the
 *    assertion that keeps the port honest: a retyped needle, a misplaced blank
 *    line or a wrong block boundary all show up here.
 *  - the remaining tests pin the behaviour the device depends on: the four
 *    structural edits really land on the real SDK, a second pass is a no-op, and
 *    the string pass stays inside the Facebook SDK.
 */
class SmaliPatchesTest {

    @Test
    fun `the port produces the same smali as the reference patcher`() {
        SmaliFixtures.requireApk()
        val (repo, python) = SmaliFixtures.requireReference()

        val data = SmaliFixtures.dexBytes("classes.dex")
        val work = SmaliFixtures.workDir("smali-equivalence")
        val ours = SmaliFixtures.disassembleTree(data, File(work, "ours"))
        val reference = SmaliFixtures.disassembleTree(data, File(work, "reference"))
        assertTrue("baksmali produced no classes", SmaliFixtures.snapshot(SmaliFixtures.smaliRoot(ours)).isNotEmpty())

        val edits = DexPatcher.applyEdits(SmaliFixtures.smaliRoot(ours), SmaliFixtures.AUTH_HOST)
        println(edits.edits.joinToString("\n"))
        assertEquals("the port changed no smali file", true, edits.rewritten > 0)

        val driver = File(SmaliFixtures.repoRoot(), "tools/reference-smali-patch.py")
        assertTrue("missing ${driver.absolutePath}", driver.isFile)
        val output = SmaliFixtures.run(
            listOf(
                python.absolutePath, "-X", "utf8", driver.absolutePath,
                repo.absolutePath, reference.absolutePath, SmaliFixtures.AUTH_HOST,
            ),
        )
        println(output)

        val mine = SmaliFixtures.snapshot(SmaliFixtures.smaliRoot(ours))
        val theirs = SmaliFixtures.snapshot(SmaliFixtures.smaliRoot(reference))
        assertTrue(
            "the reference patched nothing - the driver did not run against the tree",
            theirs.values.any { it.contains(SmaliFixtures.AUTH_HOST) },
        )
        assertEquals("different file sets", theirs.keys.sorted(), mine.keys.sorted())
        mine.forEach { (relative, text) ->
            assertEquals("$relative differs from the reference patcher", theirs[relative], text)
        }
    }

    @Test
    fun `every reference edit lands on the real SDK and a second pass does nothing`() {
        SmaliFixtures.requireApk()
        val data = SmaliFixtures.dexBytes("classes.dex")
        val tree = SmaliFixtures.disassembleTree(data, SmaliFixtures.workDir("smali-edits"))
        val smaliRoot = SmaliFixtures.smaliRoot(tree)

        val first = DexPatcher.applyEdits(smaliRoot, SmaliFixtures.AUTH_HOST)
        println(first.edits.joinToString("\n"))
        assertEquals(
            "expected one outcome per structural edit",
            SmaliPatches.STRUCTURAL_FILES.size,
            first.edits.size,
        )
        first.edits.forEach { edit ->
            assertEquals("${edit.name} (${edit.detail})", SmaliEditOutcome.APPLIED, edit.outcome)
        }
        assertTrue(
            "the SDK domain was not rewritten: ${first.hits}",
            first.hits.keys.any { it.contains("facebook.com") },
        )
        assertTrue(
            "the auth host never reached the smali",
            SmaliFixtures.snapshot(smaliRoot).values.any { it.contains("\"${SmaliFixtures.AUTH_HOST}\"") },
        )

        // The device flow depends on these four, so check the actual text rather
        // than trusting the outcome enum.
        val utils = File(smaliRoot, SmaliPatches.CUSTOM_TAB_UTILS).readText()
        assertTrue(
            "getValidRedirectURI no longer returns the default URI",
            utils.contains("CustomTabUtils;->getDefaultRedirectURI()Ljava/lang/String;"),
        )
        val login = File(smaliRoot, SmaliPatches.LOGIN_BEHAVIOR).readText()
        listOf("v4", "v7", "v8", "v9").forEach { register ->
            assertTrue(
                "NATIVE_WITH_FALLBACK still allows $register",
                login.contains("const/4 $register, 0x0"),
            )
        }
        val customTab = File(smaliRoot, SmaliPatches.CUSTOM_TAB).readText()
        assertTrue("openCustomTab still uses CustomTabsIntent", customTab.contains("android.intent.action.VIEW"))
        val dialog = File(smaliRoot, SmaliPatches.WEB_DIALOG_CLIENT).readText()
        assertTrue(
            "the API-24 overload was not added",
            dialog.contains(SmaliPatchData.WEBDIALOG_SIGNATURE),
        )

        // A second pass is a no-op - and, like the reference, reports the markers as
        // gone rather than pretending it applied them again.
        val before = SmaliFixtures.snapshot(smaliRoot)
        val second = DexPatcher.applyEdits(smaliRoot, SmaliFixtures.AUTH_HOST)
        assertEquals("a second pass rewrote files", 0, second.rewritten)
        assertEquals(before, SmaliFixtures.snapshot(smaliRoot))
        assertTrue("a second pass looks unpatchable rather than already patched", second.alreadyPatched)
    }

    @Test
    fun `the generated patch data still matches the reference`() {
        val (repo, python) = SmaliFixtures.requireReference()
        val out = SmaliFixtures.workDir("smali-data")
        val generator = File(SmaliFixtures.repoRoot(), "tools/gen-smali-patches.py")
        assertTrue("missing ${generator.absolutePath}", generator.isFile)

        SmaliFixtures.run(
            listOf(python.absolutePath, "-X", "utf8", generator.absolutePath, repo.absolutePath, out.absolutePath),
        )
        val regenerated = File(out, "SmaliPatchData.kt")
        assertTrue("the generator produced nothing", regenerated.isFile)

        val checkedIn = File(
            SmaliFixtures.repoRoot(),
            "app/src/main/java/dev/lunartear/host/patch/apk/SmaliPatchData.kt",
        )
        assertEquals(
            "SmaliPatchData.kt is stale - re-run tools/gen-smali-patches.py",
            regenerated.readText(),
            checkedIn.readText(),
        )
    }

    @Test
    fun `the string pass stays inside the facebook sdk`() {
        SmaliFixtures.requireApk()
        val data = SmaliFixtures.dexBytes("classes.dex")
        val (patched, report) = DexPatcher.patch(
            data, "classes.dex", SmaliFixtures.AUTH_HOST, SmaliFixtures.workDir("smali-scope"),
        )
        assertNotNull("patching failed: ${report.failures}", patched)

        // The reference reaches the 'https' -> 'http' rewrite through the smali of
        // com/facebook/** only. Doing it everywhere (as an earlier version of this
        // patcher did, straight on the interned pool string) also rewrites unrelated
        // Java code that happens to use the literal.
        assertEquals(
            "the redirect leaked outside the Facebook SDK",
            classesReferencing(data, "https").filterNot { it.startsWith("Lcom/facebook/") }.sorted(),
            classesReferencing(patched!!, "https").filterNot { it.startsWith("Lcom/facebook/") }.sorted(),
        )
    }

    /** Class types whose code loads [literal] via const-string. */
    private fun classesReferencing(data: ByteArray, literal: String): List<String> {
        val dexFile = DexBackedDexFile(Opcodes.getDefault(), DexBuffer(data))
        val out = mutableListOf<String>()
        for (classDef in dexFile.classes) {
            val hit = classDef.methods.any { method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.referenceType == ReferenceType.STRING &&
                        (instruction.reference as? StringReference)?.string == literal
                } == true
            }
            if (hit) out += classDef.type
        }
        return out
    }
}
