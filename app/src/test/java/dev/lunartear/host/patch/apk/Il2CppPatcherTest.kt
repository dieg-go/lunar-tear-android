package dev.lunartear.host.patch.apk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Verifies the libil2cpp.so patch table against the **real** 121 MB binary from
 * the 3.7.1 client (arm64-v8a). The file is not committed; tests skip themselves
 * when `tools/.cache/corpus` is empty.
 */
class Il2CppPatcherTest {

    private val corpus = File(System.getProperty("lt.corpus") ?: "tools/.cache/corpus")
    private val so: File = File(corpus, "libil2cpp.so")

    private fun requireCorpus() = assumeTrue(
        "game corpus not present at ${corpus.absolutePath}; skipping real-binary test",
        so.isFile,
    )

    private fun readAt(file: File, offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val buffer = ByteArray(length)
        raf.seek(offset)
        raf.readFully(buffer)
        buffer
    }

    @Test
    fun `every recorded pre-image matches the real binary`() {
        requireCorpus()
        val patches = Il2CppPatcher.patchesFor(8003)
        assertEquals(13, patches.size)
        patches.forEach { patch ->
            assertTrue(
                "${patch.name}: offset 0x${"%X".format(patch.offset)} is past end of file",
                patch.offset + patch.bytes.size <= so.length(),
            )
            val actual = readAt(so, patch.offset, patch.preImage.size)
            assertArrayEquals(
                "${patch.name} @ 0x${"%X".format(patch.offset)}: pre-image does not match the binary",
                patch.preImage,
                actual,
            )
        }
    }

    @Test
    fun `a port below 1024 is still encoded correctly`() {
        // 443 is the client default and must NOT be patched; anything else must.
        val defaultPatches = Il2CppPatcher.patchesFor(443)
        assertEquals(10, defaultPatches.size)
        assertTrue(defaultPatches.none { it.portOverride })

        val custom = Il2CppPatcher.patchesFor(8003)
        assertEquals(3, custom.count { it.portOverride })
        assertEquals(8003, Arm64.decodeMovzW(custom.first { it.name.contains("get_ServerPort") }.bytes)!!.second)
    }

    @Test
    fun `patches the real binary in place with all 13 sites`() {
        requireCorpus()
        val data = so.readBytes()
        val sizeBefore = data.size
        val result = Il2CppPatcher.patch(data, Il2CppPatcher.patchesFor(8003))

        assertEquals("expected no mismatches\n${result.summary()}", 0, result.mismatches.size)
        assertEquals(13, result.applied)
        assertEquals(0, result.alreadyApplied)
        assertEquals("patching must not resize the binary", sizeBefore, data.size)

        // Every site now holds exactly the intended instruction words.
        Il2CppPatcher.patchesFor(8003).forEach { patch ->
            val at = patch.offset.toInt()
            assertArrayEquals(
                "${patch.name} was not written",
                patch.bytes,
                data.copyOfRange(at, at + patch.bytes.size),
            )
        }

        // The port overrides must decode back to the requested port.
        val expectedPorts = mapOf(
            "NetworkConfig.get_ServerPort" to 0,
            "InitializeApiClient.OnStateBegin" to 2,
            "CalculatorNetworking.InitializeApiClient" to 20,
        )
        expectedPorts.forEach { (name, register) ->
            val patch = Il2CppPatcher.patchesFor(8003).first { it.name == name }
            val at = patch.offset.toInt()
            val decoded = Arm64.decodeMovzW(data.copyOfRange(at, at + 4))
            assertEquals("$name did not encode MOVZ", register, decoded!!.first)
            assertEquals("$name did not encode port 8003", 8003, decoded.second)
        }
    }

    @Test
    fun `refuses to patch a binary whose pre-image does not match`() {
        val patches = Il2CppPatcher.patchesFor(8003)
        // Synthetic buffer: correct pre-images everywhere, then corrupt one site.
        val size = (patches.maxOf { it.offset } + 16).toInt()
        val data = ByteArray(size)
        patches.forEach { patch ->
            System.arraycopy(patch.preImage, 0, data, patch.offset.toInt(), patch.preImage.size)
        }
        val target = patches.first { it.name == "HandleNet.Encrypt" }
        data[target.offset.toInt()] = 0x7F

        try {
            Il2CppPatcher.patch(data, patches)
            assertTrue("expected a refusal for a mismatched pre-image", false)
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does not match the expected 3.7.1"))
            assertTrue(expected.message!!.contains("HandleNet.Encrypt"))
        }

        // Non-strict mode reports instead of throwing, and still leaves the
        // mismatching site alone.
        val lenient = Il2CppPatcher.patch(data, patches, strict = false)
        assertEquals(1, lenient.mismatches.size)
        assertEquals(12, lenient.applied)
        assertEquals(0x7F.toByte(), data[target.offset.toInt()])
    }

    @Test
    fun `patching an already patched binary changes nothing`() {
        val patches = Il2CppPatcher.patchesFor(8003)
        val size = (patches.maxOf { it.offset } + 16).toInt()
        val data = ByteArray(size)
        patches.forEach { patch ->
            System.arraycopy(patch.bytes, 0, data, patch.offset.toInt(), patch.bytes.size)
        }
        val snapshot = data.copyOf()

        val result = Il2CppPatcher.patch(data, patches)
        assertEquals(0, result.applied)
        assertEquals(13, result.alreadyApplied)
        assertArrayEquals(snapshot, data)
    }
}
