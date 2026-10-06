package dev.lunartear.host.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Minimal ELF header + PT_INTERP reader — enough to prove the file is an AArch64 static PIE. */
data class ElfInfo(
    val bits: Int,
    val littleEndian: Boolean,
    val machine: Int,
    val type: Int,
    val interpreter: String?,
) {
    val is64: Boolean get() = bits == 64
    val machineName: String get() = when (machine) {
        183 -> "AArch64"
        62 -> "x86-64"
        40 -> "ARM"
        else -> "machine#$machine"
    }
    val typeName: String get() = when (type) {
        2 -> "EXEC"
        3 -> "DYN (PIE)"
        else -> "type#$type"
    }
    val isPie: Boolean get() = type == 3
    val summary: String
        get() = buildString {
            append("ELF$bits ${machineName} $typeName")
            interpreter?.let { append("  interp=$it") }
        }

    companion object {
        private const val PT_INTERP = 3

        fun read(file: File): ElfInfo? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(64)
                if (raf.read(head) < 64) return null
                if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                    head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
                ) return null

                val bits = if (head[4].toInt() == 2) 64 else 32
                val le = head[5].toInt() == 1
                fun u16(off: Int): Int = if (le) {
                    (head[off].toInt() and 0xFF) or ((head[off + 1].toInt() and 0xFF) shl 8)
                } else {
                    (head[off + 1].toInt() and 0xFF) or ((head[off].toInt() and 0xFF) shl 8)
                }
                fun u32(off: Int): Long = if (le) {
                    ((head[off].toLong() and 0xFF)) or ((head[off + 1].toLong() and 0xFF) shl 8) or
                        ((head[off + 2].toLong() and 0xFF) shl 16) or ((head[off + 3].toLong() and 0xFF) shl 24)
                } else {
                    ((head[off + 3].toLong() and 0xFF)) or ((head[off + 2].toLong() and 0xFF) shl 8) or
                        ((head[off + 1].toLong() and 0xFF) shl 16) or ((head[off].toLong() and 0xFF) shl 24)
                }
                fun u64(off: Int): Long {
                    val lo = u32(off)
                    val hi = u32(off + 4)
                    return lo or (hi shl 32)
                }

                val machine = u16(18)
                val type = u16(16)
                val phOff = if (bits == 64) u64(0x20) else u32(0x1C).toLong()
                val phEntSize = u16(if (bits == 64) 0x36 else 0x2A)
                val phNum = u16(if (bits == 64) 0x38 else 0x2C)

                var interp: String? = null
                if (phOff > 0 && phEntSize > 0) {
                    for (i in 0 until phNum) {
                        val base = phOff + i.toLong() * phEntSize
                        val entry = ByteArray(phEntSize)
                        raf.seek(base)
                        if (raf.read(entry) < phEntSize) break
                        fun e32(o: Int): Long = ((entry[o].toLong() and 0xFF)) or
                            ((entry[o + 1].toLong() and 0xFF) shl 8) or
                            ((entry[o + 2].toLong() and 0xFF) shl 16) or
                            ((entry[o + 3].toLong() and 0xFF) shl 24)
                        fun e64(o: Int): Long = e32(o) or (e32(o + 4) shl 32)
                        val pType = e32(0)
                        if (pType == PT_INTERP.toLong()) {
                            val offset = if (bits == 64) e64(8) else e32(4)
                            val size = if (bits == 64) e64(32) else e32(16)
                            if (size in 1..4096) {
                                val buf = ByteArray(size.toInt())
                                raf.seek(offset)
                                raf.read(buf)
                                interp = buf.toString(Charsets.UTF_8).trimEnd('\u0000')
                            }
                            break
                        }
                    }
                }
                ElfInfo(bits = bits, littleEndian = le, machine = machine, type = type, interpreter = interp)
            }
        }.getOrNull()
    }
}

/** One bundled binary, checked against what tools/build-native.ps1 recorded. */
data class BinaryCheck(
    val fileName: String,
    val goPackage: String,
    val abi: String,
    val path: String?,
    val exists: Boolean,
    val sizeBytes: Long,
    val expectedBytes: Long,
    val sha256: String?,
    val expectedSha256: String,
    val executable: Boolean,
    val elf: ElfInfo?,
) {
    val sizeMatches: Boolean get() = exists && sizeBytes == expectedBytes
    val hashMatches: Boolean get() = sha256 != null && sha256.equals(expectedSha256, ignoreCase = true)
    val elfOk: Boolean get() = elf != null && elf.is64 && elf.isPie
    val pass: Boolean get() = exists && sizeMatches && hashMatches && executable && elfOk

    val problem: String?
        get() = when {
            !exists -> "not extracted to nativeLibraryDir"
            !sizeMatches -> "size $sizeBytes != expected $expectedBytes"
            !hashMatches -> "sha256 mismatch (expected ${expectedSha256.take(12)}…, got ${sha256?.take(12)}…)"
            !executable -> "not marked executable"
            elf == null -> "not an ELF file"
            !elf.is64 -> "not 64-bit"
            !elf.isPie -> "not a PIE (${elf.typeName})"
            else -> null
        }
}

/** Full Phase-0 diagnostic: are the bundled binaries present, intact and runnable? */
data class NativeReport(
    val deviceAbi: String,
    val deviceAbis: List<String>,
    val nativeLibraryDir: String,
    val upstreamCommit: String,
    val generatedAt: String,
    val checks: List<BinaryCheck>,
) {
    val allPass: Boolean get() = checks.isNotEmpty() && checks.all { it.pass }
    val failing: List<BinaryCheck> get() = checks.filterNot { it.pass }
}

object NativeProbe {

    fun probe(context: Context): NativeReport {
        val abis = Build.SUPPORTED_ABIS.toList()
        val primary = abis.firstOrNull() ?: "arm64-v8a"
        val libDir = context.applicationInfo.nativeLibraryDir
        val expected = NativeManifest.forAbi(primary)

        val checks = expected.map { bin ->
            val file = File(libDir, bin.fileName)
            val exists = file.isFile
            BinaryCheck(
                fileName = bin.fileName,
                goPackage = bin.goPackage,
                abi = bin.abi,
                path = if (exists) file.absolutePath else null,
                exists = exists,
                sizeBytes = if (exists) file.length() else 0L,
                expectedBytes = bin.bytes,
                sha256 = if (exists) sha256(file) else null,
                expectedSha256 = bin.sha256,
                executable = exists && file.canExecute(),
                elf = if (exists) ElfInfo.read(file) else null,
            )
        }

        return NativeReport(
            deviceAbi = primary,
            deviceAbis = abis,
            nativeLibraryDir = libDir,
            upstreamCommit = NativeManifest.UPSTREAM_SHORT,
            generatedAt = NativeManifest.GENERATED_AT,
            checks = checks,
        )
    }

    /**
     * Runs a bundled binary with a harmless argument.
     *
     * `--help` is handled by Go's flag package before any application code runs:
     * usage goes to stderr and the process exits 2 without binding ports, opening
     * databases or touching the network. Any exit code at all proves the binary
     * was exec'd by the Android linker and the Go runtime started.
     */
    fun smokeTest(context: Context, fileName: String, timeoutMs: Long = 20_000): ExecResult {
        val path = File(context.applicationInfo.nativeLibraryDir, fileName).absolutePath
        return NativeExec.run(listOf(path, "--help"), timeoutMs = timeoutMs)
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
