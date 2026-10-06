package dev.lunartear.host.patch.apk

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AArch64 instruction encodings used by the patches, with a small decoder for verification. */
object Arm64 {

    fun word(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    val NOP: ByteArray = word(0xD503201F.toInt())
    val RET: ByteArray = word(0xD65F03C0.toInt())
    val MOV_X0_0: ByteArray = word(0xD2800000.toInt())   // mov x0, #0
    val MOV_W0_1: ByteArray = word(0x52800020.toInt())   // mov w0, #1
    val MOV_X0_X1: ByteArray = word(0xAA0103E0.toInt())  // mov x0, x1

    /**
     * `MOVZ W<rd>, #imm16`.
     *
     * Encoding: 0101 0010 100 imm16 Rd  -> 0x52800000 | (imm << 5) | rd
     * This is how the reference patcher forces the gRPC port.
     */
    fun movzW(rd: Int, imm: Int): ByteArray {
        require(rd in 0..30) { "rd out of range: $rd" }
        require(imm in 0..0xFFFF) { "imm out of range: $imm" }
        return word(0x52800000.toInt() or (imm shl 5) or rd)
    }

    /** Decodes `MOVZ W<rd>, #imm16`, or null when [bytes] is not one. */
    fun decodeMovzW(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4) return null
        val value = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        // Fixed bits: 31..23 (sf=0, opc=10, 100101) and the hw field (22..21) = 00.
        // The register and immediate fields are deliberately left unmasked.
        if ((value and 0xFFE00000.toInt()) != 0x52800000.toInt()) return null
        val imm = (value ushr 5) and 0xFFFF
        val rd = value and 0x1F
        return rd to imm
    }
}

/**
 * One rewrite site in `libil2cpp.so`.
 *
 * [offset] is used as a **file offset**, exactly like the reference script
 * (`f.seek(rva)`), which works because the relevant segments of this binary are
 * mapped at their file offsets.
 */
data class Il2CppPatch(
    val name: String,
    val offset: Long,
    val preImage: ByteArray,
    val bytes: ByteArray,
    val description: String,
    val portOverride: Boolean = false,
)

sealed interface Il2CppOutcome {
    data class Applied(val patch: Il2CppPatch) : Il2CppOutcome
    data class AlreadyApplied(val patch: Il2CppPatch) : Il2CppOutcome
    data class Mismatch(val patch: Il2CppPatch, val found: String) : Il2CppOutcome
}

data class Il2CppPatchResult(val outcomes: List<Il2CppOutcome>) {
    val applied: Int get() = outcomes.count { it is Il2CppOutcome.Applied }
    val alreadyApplied: Int get() = outcomes.count { it is Il2CppOutcome.AlreadyApplied }
    val mismatches: List<Il2CppOutcome.Mismatch> get() = outcomes.filterIsInstance<Il2CppOutcome.Mismatch>()

    fun summary(): String = buildString {
        append("libil2cpp.so: ${applied} applied, ${alreadyApplied} already applied, ${mismatches.size} mismatched\n")
        outcomes.forEach { outcome ->
            when (outcome) {
                is Il2CppOutcome.Applied -> append("  [patched]  ${outcome.patch.name} @ 0x${"%X".format(outcome.patch.offset)}\n")
                is Il2CppOutcome.AlreadyApplied -> append("  [already]  ${outcome.patch.name} @ 0x${"%X".format(outcome.patch.offset)}\n")
                is Il2CppOutcome.Mismatch -> append("  [MISMATCH] ${outcome.patch.name} @ 0x${"%X".format(outcome.patch.offset)}: found ${outcome.found}\n")
            }
        }
    }
}

/**
 * Patcher for the client's `lib/arm64-v8a/libil2cpp.so`.
 *
 * Differences from the reference implementation, both deliberate:
 *
 *  1. Every site is verified against the pre-image recorded from the real
 *     3.7.1 (versionCode 152, arm64-v8a) binary before anything is written, so a
 *     different client build is rejected instead of silently corrupted.
 *  2. Re-patching an already patched file is a no-op rather than a rewrite.
 *
 * The patch list is otherwise identical to `IL2CPP_PATCHES` in
 * lunar-scripts/android/patch_apk.py, and the three port overrides are appended
 * only when the gRPC port is not 443 - an unprivileged Android app cannot bind
 * 443, so on-device hosting always needs them.
 */
object Il2CppPatcher {

    /** Ports the client uses unless the port patches are applied. */
    const val DEFAULT_GRPC_PORT = 443

    /** The 10 unconditional patches, with pre-images taken from the 3.7.1 binary. */
    val BASE_PATCHES: List<Il2CppPatch> = listOf(
        Il2CppPatch(
            name = "ToNativeCredentials",
            offset = 0x35C8670L,
            preImage = hex("f44fbea9fd7b01a9"),
            bytes = Arm64.MOV_X0_0 + Arm64.RET,
            description = "SSL bypass - return NULL to force an insecure gRPC channel",
        ),
        Il2CppPatch(
            name = "HandleNet.Encrypt",
            offset = 0x279410CL,
            preImage = hex("f50f1df8f44f01a9"),
            bytes = Arm64.MOV_X0_X1 + Arm64.RET,
            description = "encryption passthrough - return the payload as is",
        ),
        Il2CppPatch(
            name = "HandleNet.Decrypt",
            offset = 0x279420CL,
            preImage = hex("f50f1df8f44f01a9"),
            bytes = Arm64.MOV_X0_X1 + Arm64.RET,
            description = "decryption passthrough - return the message as is",
        ),
        Il2CppPatch(
            name = "OctoManager.Internal.GetListAes",
            offset = 0x4C27038L,
            preImage = hex("f70f1cf8f65701a9"),
            bytes = Arm64.MOV_X0_0 + Arm64.RET,
            description = "force a plain list.bin (return false = no AES)",
        ),
        Il2CppPatch(
            name = "PurchaseRealProductAsync.MoveNext(IsInitialized)",
            offset = 0x2831CA8L,
            preImage = hex("e8160034"),
            bytes = Arm64.NOP,
            description = "IAP bypass - NOP the cbz that branches to PurchasingUnavailable",
        ),
        Il2CppPatch(
            name = "Purchaser.IsExistProduct",
            offset = 0x282CE78L,
            preImage = hex("ff4301d1f51300f9"),
            bytes = Arm64.MOV_W0_1 + Arm64.RET,
            description = "IAP bypass - always report the product as existing",
        ),
        Il2CppPatch(
            name = "Purchaser.<BuyProduct>d__24.MoveNext",
            offset = 0x2834028L,
            preImage = hex("f51a00b4"),
            bytes = hex("751600b4"),
            description = "IAP bypass - redirect the null _storeController path from NRE to cancelled",
        ),
        Il2CppPatch(
            name = "PurchaseRealProductAsync.MoveNext(skipAlert)",
            offset = 0x2831CACL,
            preImage = hex("610a43a9"),
            bytes = hex("19000014"),
            description = "fast purchase - skip CheckPurchasingAlert and the CESA dialog",
        ),
        Il2CppPatch(
            name = "Initialize.MoveNext",
            offset = 0x2830834L,
            preImage = hex("68000034"),
            bytes = Arm64.NOP,
            description = "fast purchase - NOP the cbz so Initialize skips the ~8s Play timeout",
        ),
        Il2CppPatch(
            name = "TitleScreen.InitializeMenuButton",
            offset = 0x2F11900L,
            preImage = hex("f50f1df8"),
            bytes = Arm64.RET,
            description = "EOS bypass - skip the kHideMenuButtonUnixTime check",
        ),
    )

    /** The 3 sites that pin the gRPC port; appended only when port != 443. */
    fun portPatches(port: Int): List<Il2CppPatch> {
        require(port in 1..65535) { "port out of range: $port" }
        return listOf(
            Il2CppPatch(
                name = "NetworkConfig.get_ServerPort",
                offset = 0x361D548L,
                preImage = hex("01000014f44fbea9"),
                bytes = Arm64.movzW(0, port) + Arm64.RET,
                description = "gRPC port override - return $port instead of the serialized 443",
                portOverride = true,
            ),
            Il2CppPatch(
                name = "InitializeApiClient.OnStateBegin",
                offset = 0x2DEAF68L,
                preImage = hex("e203002a"),
                bytes = Arm64.movzW(2, port),
                description = "conductor port override - MOVZ W2, #$port before InitializeApiClient",
                portOverride = true,
            ),
            Il2CppPatch(
                name = "CalculatorNetworking.InitializeApiClient",
                offset = 0x2E1B278L,
                preImage = hex("f403022a"),
                bytes = Arm64.movzW(20, port),
                description = "catch-all port override - MOVZ W20, #$port replaces the saved port param",
                portOverride = true,
            ),
        )
    }

    fun patchesFor(port: Int): List<Il2CppPatch> =
        if (port == DEFAULT_GRPC_PORT) BASE_PATCHES else BASE_PATCHES + portPatches(port)

    /**
     * Applies [patches] to [data] in place.
     *
     * When [strict] is set (the default) the whole operation is all-or-nothing:
     * every site is classified first and, if any site matches neither its
     * pre-image nor the intended patch, nothing is written and an
     * [IllegalStateException] names the offending sites. A half-patched binary
     * would be worse than an unpatched one.
     */
    fun patch(data: ByteArray, patches: List<Il2CppPatch>, strict: Boolean = true): Il2CppPatchResult {
        val outcomes = mutableListOf<Il2CppOutcome>()
        val toWrite = mutableListOf<Pair<Il2CppPatch, Int>>()

        for (patch in patches) {
            val endLong = patch.offset + patch.bytes.size
            if (endLong > data.size) {
                outcomes += Il2CppOutcome.Mismatch(patch, "offset past end of file (${data.size} bytes)")
                continue
            }
            val at = patch.offset.toInt()
            val end = endLong.toInt()
            val current = data.copyOfRange(at, end)
            when {
                current.contentEquals(patch.bytes) -> outcomes += Il2CppOutcome.AlreadyApplied(patch)
                current.contentEquals(patch.preImage) -> {
                    toWrite += patch to at
                    outcomes += Il2CppOutcome.Applied(patch)
                }
                else -> outcomes += Il2CppOutcome.Mismatch(patch, hex(current))
            }
        }

        val mismatches = outcomes.filterIsInstance<Il2CppOutcome.Mismatch>()
        if (strict && mismatches.isNotEmpty()) {
            throw IllegalStateException(
                "libil2cpp.so does not match the expected 3.7.1 (versionCode 152) binary; " +
                    "refusing to patch (nothing was written):\n" +
                    mismatches.joinToString("\n") {
                        "  ${it.patch.name} @ 0x${"%X".format(it.patch.offset)}: found ${it.found}"
                    },
            )
        }

        toWrite.forEach { (patch, at) -> System.arraycopy(patch.bytes, 0, data, at, patch.bytes.size) }
        return Il2CppPatchResult(outcomes)
    }

    /** Reads [source] fully, patches it, and writes the same-size result to [target]. */
    fun patchFile(source: File, target: File, port: Int, strict: Boolean = true): Il2CppPatchResult {
        val data = source.readBytes()
        val result = patch(data, patchesFor(port), strict)
        target.parentFile?.mkdirs()
        target.writeBytes(data)
        return result
    }

    /**
     * Patches [file] in place, touching only the 13 patch windows.
     *
     * This is the path used on-device: the binary is 121 MB and the app's heap is
     * a few hundred MB, so it is never read into memory. The all-or-nothing
     * guarantee is kept by reading every window first and only writing once
     * nothing mismatched.
     */
    fun patchFileInPlace(file: File, port: Int, strict: Boolean = true): Il2CppPatchResult {
        val patches = patchesFor(port)
        val length = file.length()
        val outcomes = mutableListOf<Il2CppOutcome>()
        val windows = mutableListOf<Pair<Il2CppPatch, Long>>()

        java.io.RandomAccessFile(file, "rw").use { raf ->
            patches.forEach { patch ->
                val end = patch.offset + patch.bytes.size
                if (end > length) {
                    outcomes += Il2CppOutcome.Mismatch(patch, "offset past end of file ($length bytes)")
                    return@forEach
                }
                val current = ByteArray(patch.bytes.size)
                raf.seek(patch.offset)
                raf.readFully(current)
                when {
                    current.contentEquals(patch.bytes) -> outcomes += Il2CppOutcome.AlreadyApplied(patch)
                    current.contentEquals(patch.preImage) -> {
                        windows += patch to patch.offset
                        outcomes += Il2CppOutcome.Applied(patch)
                    }
                    else -> outcomes += Il2CppOutcome.Mismatch(patch, hex(current))
                }
            }

            val mismatches = outcomes.filterIsInstance<Il2CppOutcome.Mismatch>()
            if (strict && mismatches.isNotEmpty()) {
                throw IllegalStateException(
                    "libil2cpp.so does not match the expected 3.7.1 (versionCode 152) binary; " +
                        "refusing to patch (nothing was written):\n" +
                        mismatches.joinToString("\n") {
                            "  ${it.patch.name} @ 0x${"%X".format(it.patch.offset)}: found ${it.found}"
                        },
                )
            }

            windows.forEach { (patch, offset) ->
                raf.seek(offset)
                raf.write(patch.bytes)
            }
        }
        return Il2CppPatchResult(outcomes)
    }

    fun hex(value: Int): String = "%08x".format(value)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hex(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex string: $text" }
        return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
