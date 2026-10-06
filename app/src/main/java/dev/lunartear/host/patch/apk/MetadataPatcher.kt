package dev.lunartear.host.patch.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One string-literal rewrite.
 *
 * IL2CPP string literals are stored in a data blob and referenced by a
 * (length, dataIndex) pair. A replacement therefore has to be *no longer* than
 * the original: the reference patcher rewrites the length field and NUL-pads the
 * remainder, which keeps the file size identical.
 */
data class Replacement(val old: String, val new: String, val required: Boolean = true) {
    val oldBytes: ByteArray get() = old.toByteArray(Charsets.UTF_8)
    val newBytes: ByteArray get() = new.toByteArray(Charsets.UTF_8)
    val fits: Boolean get() = newBytes.size <= oldBytes.size

    override fun toString(): String = "\"$old\" -> \"$new\" (${oldBytes.size} -> ${newBytes.size} bytes)"
}

sealed interface StringOutcome {
    data class Applied(val entryIndex: Int, val dataIndex: Int, val oldLength: Int, val newLength: Int) : StringOutcome
    data object NotFoundInBlob : StringOutcome
    data object NoTableEntry : StringOutcome

    /** The new string does not fit the literal it replaces, so it was skipped. */
    data class TooLong(val oldLength: Int, val newLength: Int) : StringOutcome
}

data class MetadataPatchResult(
    val version: Int,
    val fileBytes: Int,
    val stringLiteralEntries: Int,
    val stringLiteralDataBytes: Int,
    val outcomes: List<Pair<Replacement, StringOutcome>>,
) {
    val applied: Int get() = outcomes.count { it.second is StringOutcome.Applied }
    val total: Int get() = outcomes.size
    val failures: List<Pair<Replacement, StringOutcome>>
        get() = outcomes.filter { it.second !is StringOutcome.Applied }

    fun summary(): String = buildString {
        append("global-metadata.dat v$version: $fileBytes bytes, ")
        append("$stringLiteralEntries string literals ($stringLiteralDataBytes byte blob)\n")
        outcomes.forEach { (replacement, outcome) ->
            val mark = if (outcome is StringOutcome.Applied) "patched" else "SKIPPED"
            append("  [$mark] ${replacement.old} -> ${replacement.new}")
            when (outcome) {
                is StringOutcome.Applied ->
                    append("  (entry #${outcome.entryIndex}, dataIndex=${outcome.dataIndex}, ${outcome.oldLength} -> ${outcome.newLength} bytes)")
                StringOutcome.NotFoundInBlob -> append("  (not found in the string-literal blob)")
                StringOutcome.NoTableEntry -> append("  (no string-literal table entry references this exact string)")
                is StringOutcome.TooLong -> append("  (does not fit: ${outcome.newLength} > ${outcome.oldLength} bytes)")
            }
            append('\n')
        }
    }
}

/**
 * IL2CPP `global-metadata.dat` string-literal patcher.
 *
 * Port of `patch_metadata_strings()` in lunar-scripts/android/patch_apk.py,
 * including its "first match in the blob, then the table entry with the same
 * (length, dataIndex)" resolution and its failure modes. Verified byte-for-byte
 * against the reference behaviour by MetadataPatcherTest.
 */
object MetadataPatcher {

    /** IL2CPP metadata magic, 0xFAB11BAF (only fits a signed Int). */
    val MAGIC: Int = 0xFAB11BAF.toInt()
    private const val HEADER_STRING_LITERAL_OFF = 8
    private const val HEADER_STRING_LITERAL_DATA_OFF = 16
    private const val ENTRY_SIZE = 8

    /** Patches [data] in place (no resizing) and reports what happened to each replacement. */
    fun patch(data: ByteArray, replacements: List<Replacement>): MetadataPatchResult {
        require(data.size >= 24) { "not a global-metadata.dat: only ${data.size} bytes" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.getInt(0)
        require(magic == MAGIC) {
            "not a global-metadata.dat: magic 0x${"%08X".format(magic)} != 0x${"%08X".format(MAGIC)}"
        }
        val version = buffer.getInt(4)
        val stringLiteralOff = buffer.getInt(HEADER_STRING_LITERAL_OFF)
        val stringLiteralSize = buffer.getInt(HEADER_STRING_LITERAL_OFF + 4)
        val dataOff = buffer.getInt(HEADER_STRING_LITERAL_DATA_OFF)
        val dataSize = buffer.getInt(HEADER_STRING_LITERAL_DATA_OFF + 4)

        require(stringLiteralOff >= 0 && stringLiteralSize >= 0 && dataOff >= 0 && dataSize >= 0) {
            "implausible section headers in global-metadata.dat"
        }
        require(dataOff + dataSize <= data.size) { "string-literal blob extends past end of file" }
        require(stringLiteralOff + stringLiteralSize <= data.size) { "string-literal table extends past end of file" }

        val entryCount = stringLiteralSize / ENTRY_SIZE
        val outcomes = mutableListOf<Pair<Replacement, StringOutcome>>()

        for (replacement in replacements) {
            val old = replacement.oldBytes
            val new = replacement.newBytes
            if (new.size > old.size) {
                // Reported rather than thrown: optional rewrites (the Facebook
                // domain, for instance) are best-effort and the caller decides
                // whether an unapplied one is fatal. Required rewrites are screened
                // up front by PatchRecipe.problems.
                outcomes += replacement to StringOutcome.TooLong(old.size, new.size)
                continue
            }

            val blobPos = indexOf(data, old, dataOff, dataOff + dataSize)
            if (blobPos < 0) {
                outcomes += replacement to StringOutcome.NotFoundInBlob
                continue
            }
            val dataIndex = blobPos - dataOff

            var entryIndex = -1
            for (i in 0 until entryCount) {
                val entryOff = stringLiteralOff + i * ENTRY_SIZE
                val entryLength = buffer.getInt(entryOff)
                val entryIndexValue = buffer.getInt(entryOff + 4)
                if (entryIndexValue == dataIndex && entryLength == old.size) {
                    entryIndex = i
                    break
                }
            }
            if (entryIndex < 0) {
                outcomes += replacement to StringOutcome.NoTableEntry
                continue
            }

            buffer.putInt(stringLiteralOff + entryIndex * ENTRY_SIZE, new.size)
            System.arraycopy(new, 0, data, blobPos, new.size)
            for (i in blobPos + new.size until blobPos + old.size) data[i] = 0

            outcomes += replacement to StringOutcome.Applied(
                entryIndex = entryIndex,
                dataIndex = dataIndex,
                oldLength = old.size,
                newLength = new.size,
            )
        }

        return MetadataPatchResult(
            version = version,
            fileBytes = data.size,
            stringLiteralEntries = entryCount,
            stringLiteralDataBytes = dataSize,
            outcomes = outcomes,
        )
    }

    /** Reads [source] fully into memory, patches it, and writes the same-size result to [target]. */
    fun patchFile(source: File, target: File, replacements: List<Replacement>): MetadataPatchResult {
        val data = source.readBytes()
        val result = patch(data, replacements)
        target.parentFile?.mkdirs()
        target.writeBytes(data)
        return result
    }

    /**
     * Patches [file] in place.
     *
     * 23 MB is small enough to hold in memory even on a phone, and finding a
     * string in the blob is far simpler with random access to the whole buffer.
     */
    fun patchFileInPlace(file: File, replacements: List<Replacement>): MetadataPatchResult {
        val data = file.readBytes()
        val result = patch(data, replacements)
        file.writeBytes(data)
        return result
    }

    /**
     * Reads the header without patching - used to show the user what is inside
     * the file before touching anything.
     */
    fun inspect(file: File): Triple<Int, Int, Int> {
        val head = ByteArray(24)
        file.inputStream().use { it.read(head) }
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val magic = buffer.getInt(0)
        require(magic == MAGIC) { "not a global-metadata.dat (magic 0x${"%08X".format(magic)})" }
        return Triple(buffer.getInt(4), buffer.getInt(HEADER_STRING_LITERAL_OFF + 4) / ENTRY_SIZE, buffer.getInt(HEADER_STRING_LITERAL_DATA_OFF + 4))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        if (needle.isEmpty()) return -1
        val last = to - needle.size
        var i = from
        while (i <= last) {
            if (haystack[i] == needle[0]) {
                var j = 1
                while (j < needle.size && haystack[i + j] == needle[j]) j++
                if (j == needle.size) return i
            }
            i++
        }
        return -1
    }
}
