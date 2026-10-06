package dev.lunartear.host.patch.apk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * The zip writer is the one piece with no upstream reference to compare against,
 * so it is tested on its own: round-trip contents, truncation of an existing
 * file, and the alignment invariant that `extractNativeLibs="false"` depends on.
 */
class ApkZipWriterTest {

    private val buildTools = File(System.getProperty("lt.buildtools") ?: "")

    private fun writeApk(target: File, prefillJunk: Boolean = false): File {
        if (prefillJunk) target.writeBytes(ByteArray(4096) { 0x41 })
        val stored = "hello stored entry".toByteArray()
        val deflated = ByteArray(50_000) { (it % 251).toByte() }
        val oddName = "lib/arm64-v8a/libil2cpp.so"

        ApkZipWriter(target).use { writer ->
            writer.add("AndroidManifest.xml", ApkZipWriter.DEFLATED, { ByteArray(100) { 1 }.inputStream() })
            writer.add("assets/small.bin", ApkZipWriter.DEFLATED, { deflated.inputStream() }, deflated.size.toLong())
            // An odd-length name so the alignment padding actually has to kick in.
            writer.add("x.bin", ApkZipWriter.STORED, { stored.inputStream() }, stored.size.toLong())
            writer.add(oddName, ApkZipWriter.STORED, { stored.inputStream() }, stored.size.toLong(), alignment = ApkPatcher.NATIVE_PAGE_ALIGNMENT)
            writer.finish()
        }
        return target
    }

    @Test
    fun `writes a readable zip whose entries round-trip`() {
        val out = File("build/tmp/zipwriter-1.apk")
        out.parentFile.mkdirs()
        writeApk(out)

        ZipFile(out).use { zip ->
            assertEquals(4, zip.size())
            assertEquals(100, zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }.size)
            val deflated = zip.getInputStream(zip.getEntry("assets/small.bin")).use { it.readBytes() }
            assertEquals(50_000, deflated.size)
            assertEquals(0, deflated[0].toInt())
            assertEquals(250, deflated[250].toInt() and 0xFF)
            assertEquals(
                "hello stored entry",
                zip.getInputStream(zip.getEntry("x.bin")).use { it.readBytes() }.toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `overwrites a pre-existing file completely`() {
        val out = File("build/tmp/zipwriter-2.apk")
        out.parentFile.mkdirs()
        writeApk(out, prefillJunk = true)

        // The header must be at offset 0: no leftovers from the previous content.
        val head = ByteArray(4)
        RandomAccessFile(out, "r").use { it.readFully(head) }
        assertArrayEquals(
            "the file does not start with a local file header: ${Il2CppPatcher.hex(head)}",
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            head,
        )
        ZipFile(out).use { zip -> assertEquals(4, zip.size()) }
    }

    @Test
    fun `stored entries are 4-byte aligned`() {
        val out = File("build/tmp/zipwriter-3.apk")
        out.parentFile.mkdirs()
        writeApk(out)

        val misaligned = mutableListOf<String>()
        var offset = 0L
        RandomAccessFile(out, "r").use { raf ->
            while (offset + 30 <= out.length()) {
                raf.seek(offset)
                if (readIntLe(raf) != 0x04034B50) break
                readShortLe(raf)                      // version
                readShortLe(raf)                      // flags
                val method = readShortLe(raf)
                readShortLe(raf)                      // mod time
                readShortLe(raf)                      // mod date
                readIntLe(raf)                        // crc
                val compressedSize = readIntLe(raf).toLong() and 0xFFFFFFFFL
                readIntLe(raf)                        // uncompressed size
                val nameLength = readShortLe(raf)
                val extraLength = readShortLe(raf)
                raf.seek(offset + 30)
                val name = ByteArray(nameLength).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                val dataOffset = offset + 30 + nameLength + extraLength
                val required = if (name.startsWith("lib/")) ApkPatcher.NATIVE_PAGE_ALIGNMENT.toLong() else 4L
                if (method == ApkZipWriter.STORED && dataOffset % required != 0L) {
                    misaligned += "$name at $dataOffset (wanted $required-byte alignment)"
                }
                offset = dataOffset + compressedSize
            }
        }
        assertTrue("stored entries are not correctly aligned: $misaligned", misaligned.isEmpty())
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `google zipalign accepts the output`() {
        val zipalign = listOf(File(buildTools, "zipalign.exe"), File(buildTools, "zipalign"))
            .firstOrNull { it.isFile }
        assumeTrue("zipalign not available", zipalign != null)

        val out = File("build/tmp/zipwriter-4.apk")
        out.parentFile.mkdirs()
        writeApk(out)

        val process = ProcessBuilder(zipalign!!.absolutePath, "-c", "-p", "-v", "4", out.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        assertEquals("zipalign rejected our archive:\n$output", 0, process.exitValue())
    }
}
