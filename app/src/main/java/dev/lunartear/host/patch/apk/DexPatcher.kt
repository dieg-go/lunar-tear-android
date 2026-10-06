package dev.lunartear.host.patch.apk

import com.android.tools.smali.baksmali.Baksmali
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.dexbacked.DexBuffer
import com.android.tools.smali.dexlib2.iface.Annotation
import com.android.tools.smali.dexlib2.iface.AnnotationElement
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.AnnotationEncodedValue
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.EncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File
import java.security.MessageDigest
import java.util.zip.Adler32

/** What one DEX file's smali surgery did. */
data class DexReport(
    val fileName: String,
    /** Number of com/facebook classes present in this file. */
    val facebookClasses: Int,
    /** Classes whose smali text changed. */
    val classesRewritten: Int,
    /** Files the string pass rewrote, by literal. */
    val stringHits: Map<String, Int>,
    /** The four structural edits, when their class is in this file. */
    val edits: List<SmaliEdit>,
    val fileBytesBefore: Int,
    val fileBytesAfter: Int,
    val failures: List<String> = emptyList(),
) {
    /** Edits the login flow needs: without them the client cannot log in. */
    val requiredEdits: List<String> get() = SmaliPatches.STRUCTURAL_FILES.values.toList()

    /** Required edits that did not apply (missing class, or an unknown SDK build). */
    val missing: List<String>
        get() {
            val found = edits.filter { it.outcome != SmaliEditOutcome.NOT_FOUND }.map { it.name }.toSet()
            return requiredEdits.filterNot { it in found }
        }

    /** True when the reference's domain rewrite hit and every structural edit applied. */
    val ok: Boolean get() = failures.isEmpty() && missing.isEmpty() && domainRewritten

    val domainRewritten: Boolean get() = stringHits.keys.any { it.contains("facebook.com") }

    fun render(): String = buildString {
        append("$fileName: $facebookClasses facebook classes, $classesRewritten rewritten")
        stringHits.forEach { (literal, files) -> append("\n  [strings] $literal in $files file(s)") }
        edits.forEach { append("\n  [${it.outcome.name.lowercase()}] ${it.name} ${it.detail}") }
        failures.forEach { append("\n  [failed] $it") }
        append("\n  $fileBytesBefore -> $fileBytesAfter bytes")
    }
}

/**
 * The surgery across every `classes*.dex` of one APK.
 *
 * The four structural edits all live in `classes.dex`, so a per-file verdict is
 * meaningless: a dex with no Facebook class at all is not a failure. This
 * aggregates the reports the way the gate needs - every required edit applied
 * *somewhere*, and the SDK's domain actually redirected.
 */
data class DexSurgeryResult(val reports: List<DexReport>) {

    /** Literal -> number of dex files the string pass rewrote it in. */
    val stringHits: Map<String, Int> = run {
        val hits = HashMap<String, Int>()
        reports.forEach { report ->
            report.stringHits.forEach { (literal, count) -> hits[literal] = (hits[literal] ?: 0) + count }
        }
        hits
    }

    /** The best outcome seen for each structural edit. */
    val editOutcomes: Map<String, SmaliEditOutcome> = run {
        val outcomes = HashMap<String, SmaliEditOutcome>()
        reports.flatMap { it.edits }.forEach { edit ->
            val current = outcomes[edit.name]
            if (current == null || (current == SmaliEditOutcome.NOT_FOUND && edit.outcome != SmaliEditOutcome.NOT_FOUND)) {
                outcomes[edit.name] = edit.outcome
            }
        }
        outcomes
    }

    /** Required edits that no dex carried. */
    val missing: List<String> = SmaliPatches.STRUCTURAL_FILES.values.filterNot {
        val outcome = editOutcomes[it]
        outcome != null && outcome != SmaliEditOutcome.NOT_FOUND
    }

    val domainRewritten: Boolean = stringHits.keys.any { it.contains("facebook.com") }

    val classesRewritten: Int = reports.sumOf { it.classesRewritten }

    val failures: List<String> = reports.flatMap { it.failures }

    val ok: Boolean
        get() = failures.isEmpty() && missing.isEmpty() && domainRewritten && classesRewritten > 0

    fun detail(): String = buildString {
        append("$classesRewritten facebook classes rewritten in ")
        append("${reports.count { it.classesRewritten > 0 }}/${reports.size} dex files")
        editOutcomes.forEach { (edit, outcome) -> append(", $edit=${outcome.name.lowercase()}") }
        if (!domainRewritten) append(" - the SDK domain was never rewritten")
        if (missing.isNotEmpty()) append(" - missing ${missing.joinToString()}")
        failures.forEach { append(" - $it") }
    }
}

/**
 * Applies the reference patcher's Facebook-SDK surgery to a `classes*.dex`.
 *
 * The reference edits *smali*: `patch_apk.py` runs against an apktool-decoded
 * tree and rewrites text. Four of its five edits are structural - a replaced
 * method body, a flipped enum constant, an added overload - and the client only
 * logs in when they are all present: with the string rewrites alone it reaches
 * the title screen, fails its Facebook login and never requests a single asset.
 *
 * So this is deliberately not a dexlib2 re-implementation of those bodies.
 * dexlib2's rewriter cannot add a method or retarget a branch inside a try
 * block, and hand-building the bodies would mean reproducing registers, branch
 * targets and exception handlers by hand. Instead the same tools apktool uses:
 *
 *  1. baksmali disassembles *only* the classes that can be affected - the
 *     com/facebook classes that reference one of the literals the string
 *     pass looks for, plus the four classes with structural edits. Files the
 *     reference leaves unchanged are not round-tripped at all;
 *  2. [SmaliPatches] applies the reference's edits verbatim;
 *  3. smali reassembles them into a small DEX, whose class definitions replace
 *     the originals in place;
 *  4. the result is written back through dexlib2's pool writer, which re-pools
 *     and recomputes the header.
 *
 * Working on a handful of classes rather than the whole 9 MB file is what makes
 * this affordable on a phone: the alternative (apktool's "disassemble all 10k
 * classes, then reassemble them") needs hundreds of megabytes of heap.
 */
object DexPatcher {

    private const val CHECKSUM_OFF = 8
    private const val SIGNATURE_OFF = 12
    private const val SIGNATURE_LEN = 20

    /** The API level baksmali/smali are told to target; the client's minSdk. */
    private const val API_LEVEL = 24

    private const val FACEBOOK_PREFIX = "Lcom/facebook/"

    /** DEX magic prefixes worth accepting: "dex\n035\0" and up. */
    fun isDex(data: ByteArray): Boolean =
        data.size >= 112 &&
            data[0] == 'd'.code.toByte() && data[1] == 'e'.code.toByte() &&
            data[2] == 'x'.code.toByte() && data[3] == '\n'.code.toByte() && data[7] == 0.toByte()

    /** The APK entry names that hold DEX bytecode (multidex: classes2.dex, …). */
    fun isDexEntry(name: String): Boolean = name.matches(Regex("""classes\d*\.dex"""))

    /** Aggregates per-file reports into the verdict the APK gate uses. */
    fun summarize(reports: List<DexReport>): DexSurgeryResult = DexSurgeryResult(reports)

    /**
     * Runs the surgery on one DEX file.
     *
     * Returns the unchanged input (and a report with no edits) when the file holds
     * no Facebook class the reference would touch, and `null` bytes when the patch
     * could not be produced at all - the report then explains why.
     */
    fun patch(
        data: ByteArray,
        fileName: String,
        authHost: String,
        workDir: File,
    ): Pair<ByteArray?, DexReport> {
        require(isDex(data)) { "$fileName is not a DEX file" }
        val opcodes = Opcodes.forDexVersion(dexVersion(data))
        val dexFile = DexBackedDexFile(opcodes, DexBuffer(data))

        val facebookClasses = dexFile.classes.filter { it.type.startsWith(FACEBOOK_PREFIX) }
        val targets = targetClassTypes(dexFile)
        if (targets.isEmpty()) {
            return data to DexReport(
                fileName = fileName,
                facebookClasses = facebookClasses.size,
                classesRewritten = 0,
                stringHits = emptyMap(),
                edits = emptyList(),
                fileBytesBefore = data.size,
                fileBytesAfter = data.size,
            )
        }

        val root = File(workDir, "smali-${fileName.removeSuffix(".dex")}")
        root.deleteRecursively()
        val smaliRoot = File(root, "smali")
        smaliRoot.mkdirs()

        val failures = mutableListOf<String>()
        try {
            // 1. Disassemble just the affected classes.
            val smaliRoot = File(root, "smali")
            smaliRoot.mkdirs()
            if (!disassemble(data, targets, smaliRoot)) failures += "baksmali failed on $fileName"
            val files = smaliFiles(smaliRoot)
            if (files.isEmpty()) {
                failures += "baksmali produced no smali for ${targets.size} class(es)"
                return null to report(fileName, facebookClasses.size, 0, emptyMap(), emptyList(), data.size, failures)
            }

            // 2. Apply the reference's edits.
            val tree = applyEdits(smaliRoot, authHost)
            if (tree.rewritten == 0) {
                // The markers are gone either because this file was already patched
                // or because it is a different SDK build. The pool tells them apart.
                val alreadyPatched = tree.alreadyPatched ||
                    DexBackedDexFile(Opcodes.forDexVersion(dexVersion(data)), DexBuffer(data))
                        .stringReferences.any { it.string.contains(authHost) }
                failures += if (alreadyPatched) {
                    "this dex already carries the redirect ($authHost): patch a pristine APK"
                } else {
                    "no smali file changed (SDK layout differs from the reference's)"
                }
                return null to report(fileName, facebookClasses.size, 0, tree.hits, tree.edits, data.size, failures)
            }

            // 3. Reassemble the touched classes and splice them into the original.
            val assembled = File(root, "facebook.dex")
            val smaliOptions = SmaliOptions().apply {
                apiLevel = API_LEVEL
                outputDexFile = assembled.absolutePath
            }
            if (!Smali.assemble(smaliOptions, files.map { it.absolutePath })) {
                failures += "smali failed to assemble the patched classes"
                return null to report(fileName, facebookClasses.size, tree.rewritten, tree.hits, tree.edits, data.size, failures)
            }
            if (!assembled.isFile) {
                failures += "smali produced no dex at ${assembled.name}"
                return null to report(fileName, facebookClasses.size, tree.rewritten, tree.hits, tree.edits, data.size, failures)
            }

            val patchedClasses: Map<String, ClassDef> =
                DexFileFactory.loadDexFile(assembled, opcodes).classes.associateBy { it.type }
            if (patchedClasses.isEmpty()) {
                failures += "the reassembled dex has no classes"
                return null to report(fileName, facebookClasses.size, tree.rewritten, tree.hits, tree.edits, data.size, failures)
            }

            val merged = SpliceDexFile(dexFile, patchedClasses)
            val store = MemoryDataStore()
            DexPool.writeTo(store, merged)
            val output = store.data
            if (!isDex(output)) {
                failures += "dexlib2 produced something that is not a DEX"
                return null to report(fileName, facebookClasses.size, tree.rewritten, tree.hits, tree.edits, data.size, failures)
            }
            return output to report(
                fileName, facebookClasses.size, tree.rewritten, tree.hits, tree.edits, data.size, failures,
                output.size,
            )
        } catch (t: Throwable) {
            failures += "${t::class.java.simpleName}: ${t.message}"
            return null to report(fileName, facebookClasses.size, 0, emptyMap(), emptyList(), data.size, failures)
        } finally {
            root.deleteRecursively()
        }
    }

    /** The classes the reference's text pass would touch. */
    internal fun targetClassTypes(dexFile: DexBackedDexFile): List<String> =
        dexFile.classes.filter { it.type.startsWith(FACEBOOK_PREFIX) && isTarget(it) }.map { it.type }

    /** As above, for callers holding the file bytes. */
    internal fun targetClassTypes(data: ByteArray): List<String> =
        targetClassTypes(DexBackedDexFile(Opcodes.forDexVersion(dexVersion(data)), DexBuffer(data)))

    /** Disassembles [targets] out of [data] into [smaliRoot], exactly as a patch run does. */
    internal fun disassemble(data: ByteArray, targets: List<String>, smaliRoot: File): Boolean {
        val dexFile = DexBackedDexFile(Opcodes.forDexVersion(dexVersion(data)), DexBuffer(data))
        return Baksmali.disassembleDexFile(dexFile, smaliRoot, 1, baksmaliOptions(), targets)
    }

    /**
     * The disassembler settings the reference's needles assume.
     *
     * The reference's text edits were written against apktool's decode, which emits
     * `.locals` instead of `.registers` and names labels by sequence
     * (`:cond_0`, `:try_start_0`) rather than by code address (`:cond_8`). Both are
     * baksmali defaults of the *other* setting, and a needle that says
     * "`.locals 3` then `:cond_0`" matches nothing otherwise.
     */
    private fun baksmaliOptions(): BaksmaliOptions = BaksmaliOptions().apply {
        apiLevel = API_LEVEL
        parameterRegisters = true
        localsDirective = true
        sequentialLabels = true
        debugInfo = true
    }

    internal fun smaliFiles(smaliRoot: File): List<File> =
        smaliRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".smali") }
            .sortedBy { it.path }
            .toList()

    /** What one pass over a disassembled tree did. */
    internal data class TreeEdits(
        val rewritten: Int,
        val hits: Map<String, Int>,
        val edits: List<SmaliEdit>,
        val alreadyPatched: Boolean,
    )

    /**
     * Applies the reference's edits to every file in [smaliRoot] and reports what
     * changed. Kept separate from [patch] so a test can run it against the same
     * tree the Python reference is run on and diff the two results.
     */
    internal fun applyEdits(smaliRoot: File, authHost: String): TreeEdits {
        val replacements = SmaliPatches.stringReplacements(authHost)
        val hits = HashMap<String, Int>()
        val edits = mutableListOf<SmaliEdit>()
        var rewritten = 0
        var alreadyPatched = false
        for (file in smaliFiles(smaliRoot)) {
            val relative = file.relativeTo(smaliRoot).path.replace(File.separatorChar, '/')
            val raw = file.readText()
            // The reference's needles are LF-only, and Python hides the difference:
            // `open(..., encoding="utf-8")` translates newlines on read and back on
            // write. baksmali uses the platform's separator, so on Windows its smali
            // is CRLF and a needle copied verbatim would never match. Normalise for
            // editing, write the file back the way it arrived.
            val crlf = raw.contains("\r\n")
            val original = if (crlf) raw.replace("\r\n", "\n") else raw
            if (original.contains("\"$authHost\"")) alreadyPatched = true
            val (patched, fileEdits) = SmaliPatches.applyToFile(relative, original, authHost)
            replacements.forEach { (old, _) -> if (old in original) hits[old] = (hits[old] ?: 0) + 1 }
            edits += fileEdits.filter { it.name != "strings" }
            if (patched != original) {
                file.writeText(if (crlf) patched.replace("\n", "\r\n") else patched)
                rewritten++
            }
        }
        return TreeEdits(rewritten, hits, edits, alreadyPatched)
    }

    private fun report(
        fileName: String,
        facebookClasses: Int,
        rewritten: Int,
        hits: Map<String, Int>,
        edits: List<SmaliEdit>,
        before: Int,
        failures: List<String>,
        after: Int = before,
    ) = DexReport(fileName, facebookClasses, rewritten, hits, edits, before, after, failures)

    /**
     * True when the reference's text pass could change this class: it references
     * one of the literals the pass replaces, or it carries a structural edit.
     *
     * The reference walks every com/facebook smali file, but a file with none of
     * those literals comes back out of smali byte-identical, so leaving it alone
     * is the same result for a fraction of the work.
     */
    private fun isTarget(classDef: ClassDef): Boolean {
        val path = SmaliPatches.smaliPathFor(classDef.type)
        if (path in SmaliPatches.STRUCTURAL_FILES) return true
        val wanted = SmaliPatches.SEARCH_VALUES.toHashSet()
        return stringConstants(classDef).any { it in wanted }
    }

    /** Every string constant a class carries: code, static values and annotations. */
    private fun stringConstants(classDef: ClassDef): Set<String> {
        val out = HashSet<String>()

        fun addEncoded(value: EncodedValue?) {
            when (value) {
                is StringEncodedValue -> out.add(value.value)
                is ArrayEncodedValue -> value.value.forEach { addEncoded(it) }
                is AnnotationEncodedValue -> value.elements.forEach { addEncoded(it.value) }
                else -> Unit
            }
        }

        fun addAnnotation(annotation: Annotation) {
            annotation.elements.forEach { element: AnnotationElement -> addEncoded(element.value) }
        }

        classDef.annotations.forEach { addAnnotation(it) }
        for (field: Field in classDef.fields) {
            addEncoded(field.initialValue)
            field.annotations.forEach { addAnnotation(it) }
        }
        for (method: Method in classDef.methods) {
            method.annotations.forEach { addAnnotation(it) }
            val implementation = method.implementation ?: continue
            for (instruction in implementation.instructions) {
                if (instruction !is ReferenceInstruction) continue
                if (instruction.referenceType != ReferenceType.STRING) continue
                (instruction.reference as? StringReference)?.let { out.add(it.string) }
            }
        }
        return out
    }

    /** The original file with the reassembled classes substituted in. */
    private class SpliceDexFile(
        private val original: DexFile,
        private val replacements: Map<String, ClassDef>,
    ) : DexFile {
        override fun getClasses(): Set<ClassDef> =
            original.classes.mapTo(LinkedHashSet()) { replacements[it.type] ?: it }

        override fun getOpcodes(): Opcodes = original.opcodes
    }

    /** True when the DEX header's stored checksum and signature match its contents. */
    fun verify(data: ByteArray): Pair<Boolean, Boolean> {
        val adler = Adler32()
        adler.update(data, CHECKSUM_OFF + 4, data.size - CHECKSUM_OFF - 4)
        val checksumOk = adler.value.toInt() == readIntLe(data, CHECKSUM_OFF)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(data, SIGNATURE_OFF + SIGNATURE_LEN, data.size - SIGNATURE_OFF - SIGNATURE_LEN)
        val signatureOk = digest.digest()
            .contentEquals(data.copyOfRange(SIGNATURE_OFF, SIGNATURE_OFF + SIGNATURE_LEN))
        return checksumOk to signatureOk
    }

    /** Every class type in the file; tests assert a rewrite drops none. */
    fun classNames(data: ByteArray): List<String> =
        DexBackedDexFile(Opcodes.forDexVersion(dexVersion(data)), DexBuffer(data)).classes.map { it.type }

    /** Every string value in the pool, in index order. */
    fun stringValues(data: ByteArray): List<String> =
        DexBackedDexFile(Opcodes.forDexVersion(dexVersion(data)), DexBuffer(data))
            .stringReferences.map { it.string }

    /** Every string constant the *code* actually references. */
    fun referencedStrings(data: ByteArray): Set<String> {
        val dexFile = DexBackedDexFile(Opcodes.forDexVersion(dexVersion(data)), DexBuffer(data))
        val out = HashSet<String>()
        for (classDef in dexFile.classes) {
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                for (instruction in implementation.instructions) {
                    if (instruction !is ReferenceInstruction) continue
                    if (instruction.referenceType != ReferenceType.STRING) continue
                    (instruction.reference as? StringReference)?.let { out.add(it.string) }
                }
            }
        }
        return out
    }

    private fun readIntLe(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)

    /** "dex\n037\0" -> 37. */
    private fun dexVersion(data: ByteArray): Int {
        fun digit(at: Int): Int = data[at].toInt() - '0'.code
        return digit(4) * 100 + digit(5) * 10 + digit(6)
    }
}
