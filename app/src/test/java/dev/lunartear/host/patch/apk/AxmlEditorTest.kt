package dev.lunartear.host.patch.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Verifies the AXML editor against the **real** AndroidManifest.xml from the
 * 3.7.1 APK, and cross-checks the encoding of a boolean attribute against a
 * manifest compiled by aapt2 itself, so the writer cannot drift from what the
 * platform's own toolchain produces.
 */
class AxmlEditorTest {

    private val corpus = File(System.getProperty("lt.corpus") ?: "tools/.cache/corpus")
    private val manifest: File = File(corpus, "AndroidManifest.xml")
    private val aapt2Probe = File(System.getProperty("lt.axmlprobe") ?: "tools/.cache/axmlprobe/probe.apk")

    private fun requireCorpus() = assumeTrue(
        "manifest not present at ${manifest.absolutePath}; run tools/extract-corpus.ps1",
        manifest.isFile,
    )

    @Test
    fun `reads the client identity out of the real manifest`() {
        requireCorpus()
        val bytes = manifest.readBytes()
        assertEquals(152, AxmlEditor.versionCode(bytes))
        assertEquals("3.7.1", AxmlEditor.versionName(bytes))
        assertEquals(33, AxmlEditor.targetSdk(bytes))
    }

    @Test
    fun `the real manifest has a resource map and no cleartext attribute yet`() {
        requireCorpus()
        val reader = AxmlReader(manifest.readBytes())
        assertTrue("expected a resource map chunk", reader.resourceIds.isNotEmpty())
        val application = reader.findElement("application")
        assertNotNull("expected an <application> element", application)
        assertNull(
            "the client already sets usesCleartextTraffic; the patch would be a no-op",
            reader.attrOf(application!!, AxmlAttrs.USES_CLEARTEXT_TRAFFIC),
        )
    }

    @Test
    fun `enabling cleartext adds exactly one attribute and keeps everything else`() {
        requireCorpus()
        val original = manifest.readBytes()
        val before = AxmlReader(original)
        val beforeShape = shape(before)

        val patched = AxmlEditor.enableCleartextTraffic(original)

        // Still parses, and the chunk walk still lands exactly on the end.
        val after = AxmlReader(patched)

        // The attribute is there, with the platform's resource id and a boolean value.
        val application = after.findElement("application")!!
        val attr = after.attrOf(application, AxmlAttrs.USES_CLEARTEXT_TRAFFIC)
        assertNotNull("usesCleartextTraffic was not added", attr)
        assertEquals(AxmlAttrs.TYPE_INT_BOOLEAN, attr!!.valueType)
        assertEquals(AxmlAttrs.VALUE_TRUE, attr.valueData)
        assertEquals("usesCleartextTraffic", after.attrName(attr))

        // Every original element and attribute survived unchanged.
        val afterShape = shape(after)
        assertTrue(
            "elements changed:\n before=${beforeShape.first}\n after =${afterShape.first}",
            beforeShape.first == afterShape.first,
        )
        val lostAttributes = beforeShape.second.keys - afterShape.second.keys
        assertTrue("attributes lost: $lostAttributes", lostAttributes.isEmpty())
        beforeShape.second.forEach { (key, value) ->
            assertEquals("attribute changed: $key", value, afterShape.second[key])
        }

        // Exactly one attribute was added.
        assertEquals(beforeShape.second.size + 1, afterShape.second.size)

        // Client identity is untouched by the manifest edit.
        assertEquals(152, AxmlEditor.versionCode(patched))
        assertEquals("3.7.1", AxmlEditor.versionName(patched))
    }

    /**
     * AXML requires an element's attributes to be sorted by resource id, because
     * the platform resolves them with a binary search over that list
     * (ResXMLTree::indexOfAttribute). Appending the new attribute at the end
     * produces a manifest that still installs and that aapt2 (a linear scanner)
     * still reports correctly - but on the device every lookup past the insertion
     * point resolves the wrong attribute. That is what left the patched client
     * stuck at "Loading... 60.00%" with "Failed to connect. Retrying.", never
     * requesting a single asset, while an APK built by apktool from the same
     * sources worked.
     */
    @Test
    fun `the patched manifest keeps application attributes sorted by resource id`() {
        requireCorpus()
        val patched = AxmlEditor.enableCleartextTraffic(manifest.readBytes())
        val reader = AxmlReader(patched)
        val application = reader.findElement("application")!!

        val ids = application.attrs.map { reader.attrResourceId(it) }
        assertEquals(
            "attributes are no longer sorted by resource id: " +
                ids.joinToString { "0x%08x".format(it) },
            ids.sorted(),
            ids,
        )

        // And the new attribute sits in its sorted slot rather than at the end.
        val index = application.attrs.indexOfFirst {
            reader.attrResourceId(it) == AxmlAttrs.USES_CLEARTEXT_TRAFFIC
        }
        assertTrue("usesCleartextTraffic was not found", index >= 0)
        assertTrue(
            "usesCleartextTraffic was appended instead of inserted in resource-id order",
            index < application.attrs.size - 1,
        )
    }

    @Test
    fun `enabling cleartext twice is idempotent`() {
        requireCorpus()
        val once = AxmlEditor.enableCleartextTraffic(manifest.readBytes())
        val twice = AxmlEditor.enableCleartextTraffic(once)
        assertEquals("second pass changed the manifest", once.size, twice.size)

        val reader = AxmlReader(twice)
        val application = reader.findElement("application")!!
        val count = application.attrs.count { reader.attrResourceId(it) == AxmlAttrs.USES_CLEARTEXT_TRAFFIC }
        assertEquals(1, count)
    }

    @Test
    fun `boolean attribute encoding matches what aapt2 writes`() {
        assumeTrue("aapt2 probe manifest not built; run tools/gen-manifest-attrs.ps1", aapt2Probe.isFile)
        val aapt2Manifest = ZipFile(aapt2Probe).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
        }
        val reference = AxmlReader(aapt2Manifest)
        val referenceApp = reference.findElement("application")!!
        val referenceAttr = reference.attrOf(referenceApp, AxmlAttrs.USES_CLEARTEXT_TRAFFIC)!!

        requireCorpus()
        val mine = AxmlReader(AxmlEditor.enableCleartextTraffic(manifest.readBytes()))
        val mineAttr = mine.attrOf(mine.findElement("application")!!, AxmlAttrs.USES_CLEARTEXT_TRAFFIC)!!

        assertEquals("value type differs from aapt2", referenceAttr.valueType, mineAttr.valueType)
        assertEquals("value data differs from aapt2", referenceAttr.valueData, mineAttr.valueData)
        assertEquals("rawValue differs from aapt2", referenceAttr.rawValueIdx, mineAttr.rawValueIdx)
        assertEquals("namespace differs from aapt2", referenceAttr.nsIdx >= 0, mineAttr.nsIdx >= 0)
        assertEquals(
            "namespace string differs from aapt2",
            reference.strings[referenceAttr.nsIdx],
            mine.strings[mineAttr.nsIdx],
        )
    }

    @Test
    fun `the patched manifest is accepted by aapt2`() {
        requireCorpus()
        val patched = AxmlEditor.enableCleartextTraffic(manifest.readBytes())
        val out = File(corpus, "AndroidManifest.cleartext.xml")
        out.writeBytes(patched)

        val aapt2 = File(corpus.parentFile, "sdk/build-tools/35.0.0/aapt2.exe")
        assumeTrue("aapt2 not available", aapt2.isFile)

        // aapt2 can decode a standalone binary manifest: `aapt2 dump xmltree` on a
        // zip needs an archive, so wrap it in one.
        val apk = File(corpus, "manifest-probe.apk")
        java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write(patched)
            zip.closeEntry()
        }
        val process = ProcessBuilder(aapt2.absolutePath, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertTrue("aapt2 rejected our manifest:\n$output", process.exitValue() == 0)
        assertTrue(
            "aapt2 did not report usesCleartextTraffic as true:\n$output",
            output.contains("usesCleartextTraffic") && output.contains("=true"),
        )
        assertTrue("aapt2 decode lost versionCode:\n$output", output.contains("versionCode(0x0101021b)=152"))
    }

    @Test
    fun `garbage input is rejected instead of producing a corrupt manifest`() {
        try {
            AxmlReader(ByteArray(64) { 0x41 })
            assertTrue("expected a rejection", false)
        } catch (expected: AxmlException) {
            assertTrue(expected.message!!.contains("magic"))
        }

        // A valid header with a truncated body must also be rejected.
        requireCorpus()
        val truncated = manifest.readBytes().copyOf(200)
        try {
            AxmlReader(truncated)
            assertTrue("expected a rejection for a truncated document", false)
        } catch (expected: AxmlException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    /** Element names plus every resolved (element, attribute id) -> value pair. */
    private fun shape(reader: AxmlReader): Pair<List<String>, Map<String, String>> {
        val elements = mutableListOf<String>()
        val attributes = mutableMapOf<String, String>()
        var index = 0
        reader.elements.forEach { element ->
            elements += element.name
            element.attrs.forEach { attr ->
                val id = reader.attrResourceId(attr)
                val key = if (id != 0) "${element.name}[$index]#0x${"%08X".format(id)}"
                else "${element.name}[$index]@${reader.attrName(attr)}"
                val value = when (attr.valueType) {
                    AxmlAttrs.TYPE_STRING -> "\"" + reader.strings.getOrNull(attr.valueData) + "\""
                    AxmlAttrs.TYPE_INT_DEC, AxmlAttrs.TYPE_INT_HEX, AxmlAttrs.TYPE_INT_BOOLEAN -> attr.valueData.toString()
                    else -> "t${attr.valueType}:${attr.valueData}"
                }
                attributes[key] = value
            }
            index++
        }
        return elements to attributes
    }
}
