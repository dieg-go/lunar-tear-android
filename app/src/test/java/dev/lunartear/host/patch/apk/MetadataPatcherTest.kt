package dev.lunartear.host.patch.apk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the metadata patcher against the **real** global-metadata.dat from the
 * 3.7.1 client. The file is not committed (22 MB, and it is game data); the
 * tests skip themselves when `tools/.cache/corpus` has not been populated:
 *
 *   powershell -ExecutionPolicy Bypass -File tools\extract-corpus.ps1
 */
class MetadataPatcherTest {

    private val corpus = File(System.getProperty("lt.corpus") ?: "tools/.cache/corpus")
    private val metadata: File = File(corpus, "global-metadata.dat")

    private fun requireCorpus() = assumeTrue(
        "game corpus not present at ${corpus.absolutePath}; skipping real-file test",
        metadata.isFile,
    )

    private fun header(data: ByteArray): IntArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return intArrayOf(
            buffer.getInt(0),
            buffer.getInt(4),
            buffer.getInt(8),
            buffer.getInt(12),
            buffer.getInt(16),
            buffer.getInt(20),
        )
    }

    /** The replacement set the app uses for same-device hosting. */
    private fun localReplacements(host: String = "127.0.0.1", httpPort: Int = 8080) = listOf(
        Replacement("api.app.nierreincarnation.com", host),
        Replacement(
            "https://web.app.nierreincarnation.com/assets/release/{0}/database.bin",
            "http://$host:$httpPort/assets/release/{0}/database.bin",
        ),
        Replacement("https://web.app.nierreincarnation.com", "http://$host:$httpPort"),
        Replacement("https://resources-api.app.nierreincarnation.com/", "http://$host:$httpPort/"),
    )

    @Test
    fun `real metadata has the expected magic and version`() {
        requireCorpus()
        val data = metadata.readBytes()
        val (magic, version) = MetadataPatcher.inspect(metadata).let {
            header(data)[0] to it.first
        }
        assertEquals(MetadataPatcher.MAGIC, magic)
        assertTrue("unexpected metadata version $version", version in 20..40)
    }

    @Test
    fun `every local replacement fits inside the original string`() {
        val replacements = localReplacements()
        replacements.forEach { replacement ->
            assertTrue(
                "replacement does not fit: $replacement",
                replacement.fits,
            )
        }
    }

    @Test
    fun `patches the real file in place without changing its size`() {
        requireCorpus()
        val original = metadata.readBytes()
        val before = header(original)
        val data = original.copyOf()
        val replacements = localReplacements()

        val result = MetadataPatcher.patch(data, replacements)

        assertEquals("expected all 4 strings to be patched\n${result.summary()}", 4, result.applied)
        assertEquals(original.size, data.size)

        // Header untouched: only the table entry lengths and the blob bytes move.
        assertArrayEquals(before, header(data))

        val stringLiteralOff = before[2]
        val stringLiteralSize = before[3]
        val dataOff = before[4]
        val dataSize = before[5]
        val blobEnd = dataOff + dataSize

        replacements.forEach { replacement ->
            val outcome = result.outcomes.first { it.first.old == replacement.old }.second
            assertTrue("expected $replacement to be applied, got $outcome", outcome is StringOutcome.Applied)
            val applied = outcome as StringOutcome.Applied

            val pos = dataOff + applied.dataIndex
            val old = replacement.oldBytes
            val new = replacement.newBytes
            assertArrayEquals(
                "blob does not contain the new string at the reported dataIndex",
                new,
                data.copyOfRange(pos, pos + new.size),
            )
            // NUL padding must fill exactly the remainder of the original slot.
            for (i in pos + new.size until pos + old.size) {
                assertEquals("padding byte at ${i} is not NUL", 0, data[i].toInt())
            }
            assertEquals("table entry length not updated for $replacement", new.size, applied.newLength)
        }

        // Spot check by content, not just by index: at least one of the old
        // hostnames must be gone (they are not prefixes of one another, unlike
        // the web-URL replacements).
        assertTrue(
            "the original gRPC host is still in the blob",
            indexOf(data, "api.app.nierreincarnation.com".toByteArray(), dataOff, blobEnd) < 0,
        )
    }

    @Test
    fun `a replacement longer than the original is reported and skipped, not written`() {
        requireCorpus()
        val data = metadata.readBytes()
        val before = data.copyOf()
        val tooLong = listOf(Replacement("api.app.nierreincarnation.com", "a".repeat(200)))
        val result = MetadataPatcher.patch(data, tooLong)
        assertEquals(0, result.applied)
        val outcome = result.outcomes.single().second
        assertTrue("expected TooLong, got $outcome", outcome is StringOutcome.TooLong)
        assertArrayEquals(before, data)
    }

    @Test
    fun `an absent string is reported instead of silently ignored`() {
        requireCorpus()
        val data = metadata.readBytes()
        val result = MetadataPatcher.patch(data, listOf(Replacement("this.string.is.not.in.the.client", "x")))
        assertEquals(0, result.applied)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures[0].second is StringOutcome.NotFoundInBlob)
    }

    @Test
    fun `patching twice is a no-op the second time`() {
        requireCorpus()
        val data = metadata.readBytes()
        val replacements = localReplacements()
        val first = MetadataPatcher.patch(data, replacements)
        assertEquals(4, first.applied)
        val patched = data.copyOf()

        // The originals are gone, so a second pass finds nothing to replace and
        // must leave the already-patched values untouched.
        val second = MetadataPatcher.patch(data, replacements)
        assertEquals(0, second.applied)
        assertTrue(second.outcomes.all { it.second is StringOutcome.NotFoundInBlob })
        assertArrayEquals(patched, data)

        // The patched values are still in place.
        val header = header(data)
        assertTrue(indexOf(data, "127.0.0.1".toByteArray(), header[4], header[4] + header[5]) >= 0)
    }

    /**
     * The diagnostic lever: shorten the Facebook SDK's default base domain so its
     * graph calls resolve locally and fail immediately instead of hanging on the
     * real Facebook. It only works because `facebook.com` is a standalone literal
     * with its own table entry - as a substring of a longer literal the patcher
     * would report NoTableEntry and refuse the whole patch.
     */
    @Test
    fun `facebook domain is a standalone literal that can be shortened in place`() {
        requireCorpus()
        val data = metadata.readBytes()
        val replacements = localReplacements() + Replacement("facebook.com", "127.0.0.1")
        val result = MetadataPatcher.patch(data, replacements)
        assertEquals(5, result.applied)

        val outcome = result.outcomes.single { it.first.old == "facebook.com" }.second
        assertTrue("facebook.com was not applied: $outcome", outcome is StringOutcome.Applied)
        val applied = outcome as StringOutcome.Applied
        assertEquals(12, applied.oldLength)
        assertEquals(9, applied.newLength)

        val header = header(data)
        val at = header[4] + applied.dataIndex
        assertEquals(at, indexOf(data, "127.0.0.1".toByteArray(), at, at + 9))
        // The tail the shorter literal left behind must be zeroed, not left as a
        // stale "com" that would turn the URL into "127.0.0.1com".
        for (i in at + 9 until at + 12) {
            assertTrue("stale byte at $i is ${data[i]}", data[i].toInt() == 0)
        }
    }

    private fun findEntry(data: ByteArray, tableOff: Int, tableSize: Int, dataIndex: Int, length: Int): Int {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until tableSize / 8) {
            val off = tableOff + i * 8
            if (buffer.getInt(off) == length && buffer.getInt(off + 4) == dataIndex) return i
        }
        return -1
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        val last = to - needle.size
        var i = from
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}
