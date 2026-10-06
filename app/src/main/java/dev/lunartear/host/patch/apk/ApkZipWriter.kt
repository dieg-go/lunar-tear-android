package dev.lunartear.host.patch.apk

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A zip writer that gives us the two things `java.util.zip.ZipOutputStream`
 * cannot:
 *
 *  1. **Per-entry alignment.** The client APK sets `extractNativeLibs="false"`,
 *     which requires native libraries (and `resources.arsc`) to be stored
 *     *uncompressed* and 4-byte aligned, because the loader maps them straight
 *     out of the APK. `ZipOutputStream` has no way to insert that padding.
 *  2. **Streaming large entries without buffering them.** The 121 MB
 *     `libil2cpp.so` must never be held in memory: on Android the whole process
 *     works inside a few hundred MB of heap.
 *
 * The local header is written with placeholder sizes, the data is streamed
 * through a Deflater while the CRC and sizes are counted, and the header is then
 * rewritten in place - the same trick apkzlib and zipalign use.
 *
 * Every field is written little-endian by hand: ZIP is little-endian and
 * `RandomAccessFile.writeInt` is not.
 */
class ApkZipWriter(
    private val file: File,
    private val compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
) : Closeable {

    private class Entry(
        val nameBytes: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
    )

    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val entries = mutableListOf<Entry>()

    /**
     * Entries are written in the order they are added; alignment is resolved as we go.
     *
     * [alignment] matters for uncompressed entries: a client with
     * `extractNativeLibs="false"` must have its native libraries **page aligned**
     * (4096), not merely 4-byte aligned, or the installer rejects the APK with
     * `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries`. That is
     * what `zipalign -p` does, and `zipalign -c -v 4` does not check it.
     */
    fun add(
        name: String,
        method: Int,
        source: () -> InputStream,
        sizeHint: Long = -1,
        alignment: Int = 4,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)

        // Padding so the entry's *data* starts on an [alignment] boundary, using a
        // dummy extra field the way zipalign does. The extra field itself is
        // 4 bytes of header plus the padding, and that 4 has to be part of the
        // arithmetic: dataOffset == unpadded + 4 + need.
        var extra = ByteArray(0)
        if (method == STORED && alignment > 1) {
            val unpadded = raf.filePointer + 30 + nameBytes.size
            val need = ((alignment - ((unpadded + 4) % alignment)) % alignment).toInt()
            extra = ByteArray(4 + need)
            extra[0] = 0x35
            extra[1] = 0xD9.toByte()
            extra[2] = (need and 0xFF).toByte()
            extra[3] = ((need shr 8) and 0xFF).toByte()
        }

        val localHeaderOffset = raf.filePointer
        writeLocalHeader(nameBytes, method, extra, crc = 0, compressedSize = 0, size = 0)
        val dataStart = raf.filePointer
        if (method == STORED && alignment > 1) {
            check(dataStart % alignment == 0L) {
                "entry $name: data starts at $dataStart, which is not a multiple of $alignment " +
                    "(header at $localHeaderOffset, name ${nameBytes.size} bytes, extra ${extra.size} bytes)"
            }
        }

        val crc = CRC32()
        var uncompressed = 0L
        var compressed = 0L

        source().use { input ->
            if (method == STORED) {
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    uncompressed += read
                }
                compressed = uncompressed
            } else {
                val deflater = Deflater(compressionLevel, true)
                val buffer = ByteArray(COPY_BUFFER)
                val compressedBuffer = ByteArray(COPY_BUFFER)
                try {
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        crc.update(buffer, 0, read)
                        uncompressed += read
                        deflater.setInput(buffer, 0, read)
                        while (!deflater.needsInput()) {
                            val produced = deflater.deflate(compressedBuffer)
                            if (produced <= 0) break
                            raf.write(compressedBuffer, 0, produced)
                            compressed += produced
                        }
                    }
                    deflater.finish()
                    while (!deflater.finished()) {
                        val produced = deflater.deflate(compressedBuffer)
                        if (produced <= 0) break
                        raf.write(compressedBuffer, 0, produced)
                        compressed += produced
                    }
                } finally {
                    deflater.end()
                }
            }
        }

        val end = raf.filePointer
        raf.seek(localHeaderOffset)
        writeLocalHeader(nameBytes, method, extra, crc.value, compressed, uncompressed)
        raf.seek(end)

        if (sizeHint >= 0 && sizeHint != uncompressed) {
            throw IllegalStateException("entry $name: expected $sizeHint bytes, wrote $uncompressed")
        }
        check(raf.filePointer == dataStart + compressed) { "entry $name: stream bookkeeping is off" }

        entries += Entry(nameBytes, method, crc.value, compressed, uncompressed, localHeaderOffset)
    }

    /** Writes the central directory and the end-of-central-directory record. */
    fun finish() {
        val cdStart = raf.filePointer
        entries.forEach { entry ->
            writeIntLe(0x02014B50)
            writeShortLe(20)                        // version made by
            writeShortLe(20)                        // version needed to extract
            writeShortLe(0)                         // general purpose flags
            writeShortLe(entry.method)
            writeShortLe(0)                         // mod time
            writeShortLe(0)                         // mod date
            writeIntLe(entry.crc.toInt())
            writeIntLe(entry.compressedSize.toInt())
            writeIntLe(entry.size.toInt())
            writeShortLe(entry.nameBytes.size)
            writeShortLe(0)                         // extra length
            writeShortLe(0)                         // comment length
            writeShortLe(0)                         // disk number start
            writeShortLe(0)                         // internal attributes
            writeIntLe(0x81A40000.toInt())          // external attributes: 0644
            writeIntLe(entry.localHeaderOffset.toInt())
            raf.write(entry.nameBytes)
        }
        val cdSize = raf.filePointer - cdStart

        writeIntLe(0x06054B50)
        writeShortLe(0)                             // this disk
        writeShortLe(0)                             // disk with the central directory
        writeShortLe(entries.size)                  // entries on this disk
        writeShortLe(entries.size)                  // entries in total
        writeIntLe(cdSize.toInt())
        writeIntLe(cdStart.toInt())
        writeShortLe(0)                             // comment length
    }

    private fun writeLocalHeader(
        nameBytes: ByteArray,
        method: Int,
        extra: ByteArray,
        crc: Long,
        compressedSize: Long,
        size: Long,
    ) {
        writeIntLe(0x04034B50)
        writeShortLe(20)                            // version needed
        writeShortLe(0)                             // flags
        writeShortLe(method)
        writeShortLe(0)                             // mod time
        writeShortLe(0)                             // mod date
        writeIntLe(crc.toInt())
        writeIntLe(compressedSize.toInt())
        writeIntLe(size.toInt())
        writeShortLe(nameBytes.size)
        writeShortLe(extra.size)
        raf.write(nameBytes)
        raf.write(extra)
    }

    // ZIP stores every field little-endian; RandomAccessFile writes big-endian.
    private fun writeIntLe(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
        raf.write((value ushr 16) and 0xFF)
        raf.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLe(value: Int) {
        raf.write(value and 0xFF)
        raf.write((value ushr 8) and 0xFF)
    }

    override fun close() {
        raf.close()
    }

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        private const val COPY_BUFFER = 1 shl 16

        /**
         * Walks the local file headers of [file] and returns the uncompressed
         * entries whose data does not start on [alignment].
         *
         * This is the same invariant `zipalign -c -p` checks and the one the
         * package installer enforces for an APK with
         * `extractNativeLibs="false"`. Checking it here means a packaging
         * regression shows up as a patch failure instead of an
         * `INSTALL_FAILED_INVALID_APK` on the user's phone.
         */
        fun findMisalignedStoredEntries(file: File, alignment: Int, onlyNativeLibs: Boolean = true): List<String> {
            val violations = mutableListOf<String>()
            java.io.RandomAccessFile(file, "r").use { raf ->
                var offset = 0L
                val length = file.length()
                while (offset + 30 <= length) {
                    raf.seek(offset)
                    if (readIntLe(raf) != 0x04034B50) break
                    readShortLe(raf)                     // version
                    readShortLe(raf)                     // flags
                    val method = readShortLe(raf)
                    readShortLe(raf); readShortLe(raf)   // time, date
                    readIntLe(raf)                       // crc
                    val compressedSize = readIntLe(raf).toLong() and 0xFFFFFFFFL
                    readIntLe(raf)                       // uncompressed size
                    val nameLength = readShortLe(raf)
                    val extraLength = readShortLe(raf)
                    raf.seek(offset + 30)
                    val name = ByteArray(nameLength).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                    val dataOffset = offset + 30 + nameLength + extraLength
                    val needsPageAlignment = !onlyNativeLibs || name.endsWith(".so")
                    if (method == STORED && needsPageAlignment && dataOffset % alignment != 0L) {
                        violations += "$name at $dataOffset (mod $alignment = ${dataOffset % alignment})"
                    }
                    offset = dataOffset + compressedSize
                }
            }
            return violations
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
    }
}
