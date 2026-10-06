package dev.lunartear.host.patch.apk

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlException(message: String) : Exception(message)

/** One attribute of a start element, as stored in the chunk. */
data class AxmlAttr(
    val nsIdx: Int,
    val nameIdx: Int,
    val rawValueIdx: Int,
    val valueType: Int,
    val valueData: Int,
)

/** A start element with its source chunk location. */
data class AxmlElement(
    val name: String,
    val chunkOffset: Int,
    val chunkSize: Int,
    val lineNumber: Int,
    val nsIdx: Int,
    val nameIdx: Int,
    val attributeStart: Int,
    val attributeSize: Int,
    val attrs: List<AxmlAttr>,
)

/**
 * Reader for Android binary XML (`AndroidManifest.xml` inside an APK).
 *
 * Deliberately read-mostly: it validates the whole chunk walk and exposes the
 * string pool, the resource-id map and the start elements, which is everything
 * the manifest patch needs (read versionCode/versionName, check for an existing
 * attribute, add one).
 */
class AxmlReader(private val data: ByteArray) {

    val strings: MutableList<String> = mutableListOf()
    val resourceIds: MutableList<Int> = mutableListOf()
    val elements: MutableList<AxmlElement> = mutableListOf()

    var stringPoolOffset: Int = -1
        private set
    var stringPoolSize: Int = 0
        private set
    var stringPoolHeaderSize: Int = 0
        private set
    var stringPoolFlags: Int = 0
        private set
    var stringPoolStringCount: Int = 0
        private set
    var stringPoolStyleCount: Int = 0
        private set
    var stringPoolStringsStart: Int = 0
        private set
    var stringPoolStylesStart: Int = 0
        private set
    var resourceMapOffset: Int = -1
        private set
    var resourceMapSize: Int = 0
        private set

    init {
        require(data.size >= 8) { "not an AXML file: only ${data.size} bytes" }
        // Bytes 0..3 are the wrapper chunk's type + headerSize, which is what the
        // "magic" 0x00080003 actually is; bytes 4..7 are the file size.
        val magic = u32(0)
        if (magic != MAGIC) throw AxmlException("not an AXML file: magic 0x${"%08X".format(magic)}")
        parse()
    }

    private fun parse() {
        // The XML wrapper chunk starts at offset 0 and spans the whole file.
        val wrapperType = u16(0)
        val wrapperSize = u32(4)
        if (wrapperType != TYPE_XML) {
            throw AxmlException("not an AXML document: first chunk type is 0x${"%04X".format(wrapperType)}")
        }
        if (wrapperSize != data.size) {
            throw AxmlException("AXML wrapper says $wrapperSize bytes but the file is ${data.size} bytes")
        }

        var offset = u16(2) // wrapper headerSize; the body follows it
        while (offset + 8 <= data.size) {
            val type = u16(offset)
            val headerSize = u16(offset + 2)
            val size = u32(offset + 4)
            if (size < 8 || offset + size > data.size) {
                throw AxmlException("chunk at 0x${"%X".format(offset)} (type 0x${"%04X".format(type)}) has a bad size $size")
            }
            when (type) {
                TYPE_STRING_POOL -> {
                    if (stringPoolOffset >= 0) throw AxmlException("more than one string pool")
                    readStringPool(offset, headerSize, size)
                }

                TYPE_RESOURCE_MAP -> {
                    if (resourceMapOffset >= 0) throw AxmlException("more than one resource map")
                    resourceMapOffset = offset
                    resourceMapSize = size
                    var i = offset + headerSize
                    while (i + 4 <= offset + size) {
                        resourceIds += i32(i)
                        i += 4
                    }
                }

                TYPE_START_ELEMENT -> readStartElement(offset, headerSize, size)
            }
            offset += size
        }
        if (offset != data.size) throw AxmlException("chunk walk ended at $offset but the file is ${data.size} bytes")
        if (stringPoolOffset < 0) throw AxmlException("missing the string pool")
    }

    private fun readStringPool(offset: Int, headerSize: Int, size: Int) {
        stringPoolOffset = offset
        stringPoolSize = size
        stringPoolHeaderSize = headerSize
        stringPoolStringCount = u32(offset + 8)
        stringPoolStyleCount = u32(offset + 12)
        stringPoolFlags = u32(offset + 16)
        stringPoolStringsStart = u32(offset + 20)
        stringPoolStylesStart = u32(offset + 24)

        val utf8 = stringPoolFlags and FLAG_UTF8 != 0
        var stringDataEnd = offset + size
        if (stringPoolStylesStart > 0) stringDataEnd = offset + stringPoolStylesStart

        for (i in 0 until stringPoolStringCount) {
            val stringOffset = offset + stringPoolStringsStart + u32(offset + headerSize + i * 4)
            strings += if (utf8) readUtf8String(stringOffset, stringDataEnd) else readUtf16String(stringOffset, stringDataEnd)
        }
    }

    private fun readUtf8String(at: Int, limit: Int): String {
        var p = at
        var charLen = data[p].toInt() and 0xFF
        p++
        if (charLen and 0x80 != 0) {
            charLen = (charLen and 0x7F) shl 8 or (data[p].toInt() and 0xFF)
            p++
        }
        var byteLen = data[p].toInt() and 0xFF
        p++
        if (byteLen and 0x80 != 0) {
            byteLen = (byteLen and 0x7F) shl 8 or (data[p].toInt() and 0xFF)
            p++
        }
        if (p + byteLen > limit) throw AxmlException("string at 0x${"%X".format(at)} runs past the string data")
        return String(data, p, byteLen, Charsets.UTF_8)
    }

    private fun readUtf16String(at: Int, limit: Int): String {
        var p = at
        var len = u16(p)
        p += 2
        if (len and 0x8000 != 0) {
            len = (len and 0x7FFF) shl 16 or u16(p)
            p += 2
        }
        if (p + len * 2 > limit) throw AxmlException("string at 0x${"%X".format(at)} runs past the string data")
        return String(data, p, len * 2, Charsets.UTF_16LE)
    }

    private fun readStartElement(offset: Int, headerSize: Int, size: Int) {
        val lineNumber = u32(offset + 8)
        val nsIdx = i32(offset + 16)
        val nameIdx = i32(offset + 20)
        val attributeStart = u16(offset + 24)
        val attributeSize = u16(offset + 26)
        val attributeCount = u16(offset + 28)
        val name = strings.getOrNull(nameIdx) ?: "?"

        val attrs = mutableListOf<AxmlAttr>()
        // attributeStart is relative to the attrExt struct, which begins after the
        // 8-byte node header (lineNumber + comment).
        val base = offset + 16 + attributeStart
        for (i in 0 until attributeCount) {
            val at = base + i * attributeSize
            if (at + 20 > offset + size) throw AxmlException("attribute $i of <$name> runs past the chunk")
            attrs += AxmlAttr(
                nsIdx = i32(at),
                nameIdx = i32(at + 4),
                rawValueIdx = i32(at + 8),
                valueType = data[at + 15].toInt() and 0xFF,
                valueData = i32(at + 16),
            )
        }
        elements += AxmlElement(
            name = name,
            chunkOffset = offset,
            chunkSize = size,
            lineNumber = lineNumber,
            nsIdx = nsIdx,
            nameIdx = nameIdx,
            attributeStart = attributeStart,
            attributeSize = attributeSize,
            attrs = attrs,
        )
    }

    // ---------------------------------------------------------------- lookups

    fun findElement(name: String): AxmlElement? = elements.firstOrNull { it.name == name }

    /** Resource id of an attribute, resolved through the resource map like the platform does. */
    fun attrResourceId(attr: AxmlAttr): Int = resourceIds.getOrElse(attr.nameIdx) { 0 }

    fun attrName(attr: AxmlAttr): String? = strings.getOrNull(attr.nameIdx)

    fun attrOf(element: AxmlElement, resId: Int): AxmlAttr? =
        element.attrs.firstOrNull { attrResourceId(it) == resId }

    fun intValue(attr: AxmlAttr): Int = attr.valueData

    fun stringValue(attr: AxmlAttr): String? = when (attr.valueType) {
        AxmlAttrs.TYPE_STRING -> strings.getOrNull(attr.valueData)
        else -> strings.getOrNull(attr.rawValueIdx)
    }

    /** Human-readable dump, used by tests and the dry-run report. */
    fun describe(): String = buildString {
        elements.forEach { element ->
            append("<${element.name}>")
            if (element.attrs.isNotEmpty()) {
                append(' ')
                append(
                    element.attrs.joinToString(" ") { attr ->
                        val name = attrName(attr) ?: "#${attr.nameIdx}"
                        val value = when (attr.valueType) {
                            AxmlAttrs.TYPE_STRING -> "\"" + (strings.getOrNull(attr.valueData) ?: "?") + "\""
                            AxmlAttrs.TYPE_INT_BOOLEAN -> if (attr.valueData != 0) "true" else "false"
                            AxmlAttrs.TYPE_INT_DEC -> attr.valueData.toString()
                            AxmlAttrs.TYPE_INT_HEX -> "0x${"%X".format(attr.valueData)}"
                            AxmlAttrs.TYPE_REFERENCE -> "@0x${"%08X".format(attr.valueData)}"
                            else -> "t${attr.valueType}:${attr.valueData}"
                        }
                        val id = attrResourceId(attr)
                        if (id != 0) "$name(0x${"%08X".format(id)})=$value" else "$name=$value"
                    },
                )
            }
            append('\n')
        }
    }

    private fun u16(at: Int): Int = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    private fun i32(at: Int): Int = ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun u32(at: Int): Int = i32(at)

    companion object {
        const val MAGIC = 0x00080003
        const val TYPE_XML = 0x0003
        const val TYPE_STRING_POOL = 0x0001
        const val TYPE_RESOURCE_MAP = 0x0180
        const val TYPE_START_ELEMENT = 0x0102
        const val FLAG_UTF8 = 0x100
    }
}

/**
 * Adds or updates one attribute on one start element of a binary manifest.
 *
 * Why a hand-written AXML writer: the cleartext permission has to be added to a
 * manifest that is already compiled. The conventional route (apktool + a new
 * `res/xml/network_security_config.xml`) needs `resources.arsc` surgery, because
 * a new resource file needs a new resource id. Setting
 * `android:usesCleartextTraffic="true"` on the existing `<application>` element
 * sidesteps that completely: one attribute, no new resources.
 *
 * The file is rebuilt as a chunk splice. Every index inside AXML (string
 * references, resource map lookups) is chunk-relative, so growing the string
 * pool and the resource map at their ends, and appending 20 bytes to the target
 * start element, leaves every existing reference valid.
 */
object AxmlEditor {

    private const val ATTRIBUTE_BYTES = 20

    /**
     * Sets `android:<attrName>` to a typed value on the first element named
     * [elementName]. [attrName] and [attrResId] must agree (see [AxmlAttrs]).
     */
    fun setAttribute(
        source: ByteArray,
        elementName: String,
        attrName: String,
        attrResId: Int,
        valueType: Int,
        valueData: Int,
        rawValue: Int = AxmlAttrs.NO_RAW_VALUE,
    ): ByteArray {
        val reader = AxmlReader(source)
        val element = reader.findElement(elementName)
            ?: throw AxmlException("manifest has no <$elementName> element")

        // 1. Existing attribute with this resource id: rewrite the value in place.
        val existing = reader.attrOf(element, attrResId)
        if (existing != null) {
            val at = element.chunkOffset + 16 + element.attributeStart +
                element.attrs.indexOf(existing) * element.attributeSize
            val out = source.copyOf()
            putInt(out, at + 8, rawValue)
            out[at + 12] = 8    // Res_value.size = 8 for a scalar
            out[at + 13] = 0
            out[at + 14] = 0    // res0
            out[at + 15] = valueType.toByte()
            putInt(out, at + 16, valueData)
            return out
        }

        // 2. Otherwise the attribute name has to be in the string pool, and its
        //    resource id in the map at the same index.
        val nsIdx = reader.strings.indexOf(AxmlAttrs.ANDROID_NS)
        if (nsIdx < 0) throw AxmlException("string pool has no android namespace entry")

        val nameIdx: Int
        val appendedStrings: Boolean
        if (attrName in reader.strings) {
            nameIdx = reader.strings.indexOf(attrName)
            appendedStrings = false
        } else {
            nameIdx = reader.strings.size
            reader.strings += attrName
            appendedStrings = true
        }

        // Extend the resource map so index nameIdx has the right id (entries
        // beyond the previous map length mean "no resource id" and are zero).
        val resourceIds = reader.resourceIds
        while (resourceIds.size <= nameIdx) resourceIds += 0
        resourceIds[nameIdx] = attrResId

        val newPool = if (appendedStrings) buildStringPool(reader, source) else slice(source, reader.stringPoolOffset, reader.stringPoolSize)
        val newResourceMap = buildResourceMap(reader.resourceIds)

        val newAttribute = ByteArray(ATTRIBUTE_BYTES)
        putInt(newAttribute, 0, nsIdx)
        putInt(newAttribute, 4, nameIdx)
        putInt(newAttribute, 8, rawValue)
        newAttribute[12] = 8                       // Res_value.size = 8 for a scalar
        newAttribute[13] = 0
        newAttribute[14] = 0                       // res0
        newAttribute[15] = valueType.toByte()
        putInt(newAttribute, 16, valueData)

        // 3. Splice: keep every chunk, replacing the pool, the map and the target.
        //    Bytes 0..7 are the XML wrapper's own header; the chunk body follows.
        val out = java.io.ByteArrayOutputStream(source.size + 64)
        out.write(source, 0, 8)
        var offset = 8
        while (offset < source.size) {
            val type = chunkType(source, offset)
            val size = chunkSize(source, offset)
            when {
                offset == reader.stringPoolOffset -> {
                    out.write(newPool)
                    // A pool that grew shifts every later chunk, but all AXML
                    // offsets are chunk-relative, so nothing else needs fixing.
                    // Some manifests ship without a resource map at all; without
                    // one the platform cannot match an attribute by id, so emit
                    // one right after the pool.
                    if (reader.resourceMapOffset < 0) out.write(newResourceMap)
                }

                offset == reader.resourceMapOffset -> out.write(newResourceMap)

                offset == element.chunkOffset -> {
                    // The element chunk grows by exactly one attribute. Its own
                    // header has to be fixed here rather than afterwards, because
                    // the chunk walk would otherwise stop 20 bytes early.
                    val elementBytes = source.copyOfRange(offset, offset + size)
                    putInt(elementBytes, 4, size + ATTRIBUTE_BYTES)
                    val newCount = element.attrs.size + 1
                    elementBytes[28] = newCount.toByte()
                    elementBytes[29] = (newCount shr 8).toByte()
                    val attrsEnd = 16 + element.attributeStart + element.attrs.size * element.attributeSize
                    out.write(elementBytes, 0, attrsEnd)
                    out.write(newAttribute)
                    out.write(elementBytes, attrsEnd, elementBytes.size - attrsEnd)
                }

                else -> out.write(source, offset, size)
            }
            offset += size
        }
        val result = out.toByteArray()

        // 4. There is exactly one size field that is absolute rather than
        //    chunk-relative: the XML wrapper chunk's, which covers the whole
        //    document. It sits at offset 4, before anything that can move.
        putInt(result, 4, result.size)

        // 5. Fail loudly rather than write a manifest we cannot read back.
        val check = AxmlReader(result)
        val patched = check.findElement(elementName)
            ?: throw AxmlException("internal error: <$elementName> vanished after patching")
        if (check.attrOf(patched, attrResId) == null) {
            throw AxmlException("internal error: the new attribute did not survive re-reading")
        }
        return result
    }

    /** Convenience wrapper for the cleartext flag. */
    fun enableCleartextTraffic(source: ByteArray): ByteArray =
        setAttribute(
            source = source,
            elementName = "application",
            attrName = "usesCleartextTraffic",
            attrResId = AxmlAttrs.USES_CLEARTEXT_TRAFFIC,
            valueType = AxmlAttrs.TYPE_INT_BOOLEAN,
            valueData = AxmlAttrs.VALUE_TRUE,
        )

    /** Reads `android:versionCode` from a manifest (used as a client guard). */
    fun versionCode(source: ByteArray): Int? {
        val reader = AxmlReader(source)
        val manifest = reader.findElement("manifest") ?: return null
        return reader.attrOf(manifest, AxmlAttrs.VERSION_CODE)?.let { reader.intValue(it) }
    }

    /** Reads `android:versionName` from a manifest. */
    fun versionName(source: ByteArray): String? {
        val reader = AxmlReader(source)
        val manifest = reader.findElement("manifest") ?: return null
        return reader.attrOf(manifest, AxmlAttrs.VERSION_NAME)?.let { reader.stringValue(it) }
    }

    /** Reads `android:targetSdkVersion` from a manifest. */
    fun targetSdk(source: ByteArray): Int? {
        val reader = AxmlReader(source)
        val usesSdk = reader.findElement("uses-sdk") ?: return null
        return reader.attrOf(usesSdk, AxmlAttrs.TARGET_SDK_VERSION)?.let { reader.intValue(it) }
    }

    // ---------------------------------------------------------------- encoding

    /**
     * Rebuilds the string pool with one extra string at the end.
     *
     * The original string data region is copied verbatim, so every existing
     * offset stays valid; only the offsets array, the counts and the two
     * section starts change.
     */
    private fun buildStringPool(reader: AxmlReader, source: ByteArray): ByteArray {
        val poolOff = reader.stringPoolOffset
        val stringCount = reader.stringPoolStringCount
        val styleCount = reader.stringPoolStyleCount
        val headerSize = reader.stringPoolHeaderSize
        val utf8 = reader.stringPoolFlags and AxmlReader.FLAG_UTF8 != 0

        val expectedStringsStart = headerSize + 4 * (stringCount + styleCount)
        if (reader.stringPoolStringsStart != expectedStringsStart) {
            throw AxmlException(
                "unsupported string pool layout: stringsStart=${reader.stringPoolStringsStart}, " +
                    "expected $expectedStringsStart",
            )
        }

        val dataStart = poolOff + reader.stringPoolStringsStart
        val dataEnd = if (reader.stringPoolStylesStart > 0) poolOff + reader.stringPoolStylesStart else poolOff + reader.stringPoolSize
        val originalData = source.copyOfRange(dataStart, dataEnd)
        val styles = if (reader.stringPoolStylesStart > 0) {
            source.copyOfRange(poolOff + reader.stringPoolStylesStart, poolOff + reader.stringPoolSize)
        } else {
            ByteArray(0)
        }

        val encoded = if (utf8) encodeUtf8(reader.strings.last()) else encodeUtf16(reader.strings.last())
        val added = encoded.copyOf(encoded.size + paddingFor(originalData.size + encoded.size))

        val newStringCount = stringCount + 1
        val newStringsStart = headerSize + 4 * (newStringCount + styleCount)
        val newStylesStart = if (styleCount > 0) newStringsStart + originalData.size + added.size else 0
        val totalSize = (if (styleCount > 0) newStylesStart else newStringsStart + originalData.size + added.size) + styles.size

        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(AxmlReader.TYPE_STRING_POOL.toShort())
        out.putShort(headerSize.toShort())
        out.putInt(totalSize)
        out.putInt(newStringCount)
        out.putInt(styleCount)
        out.putInt(reader.stringPoolFlags)
        out.putInt(newStringsStart)
        out.putInt(newStylesStart)
        for (i in 0 until stringCount) out.putInt(u32At(source, poolOff + headerSize + i * 4))
        out.putInt(originalData.size) // the appended string starts after the copied data
        for (i in 0 until styleCount) out.putInt(u32At(source, poolOff + headerSize + stringCount * 4 + i * 4))
        out.put(originalData)
        out.put(added)
        out.put(styles)
        return out.array()
    }

    private fun buildResourceMap(ids: List<Int>): ByteArray {
        val total = 8 + ids.size * 4
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(AxmlReader.TYPE_RESOURCE_MAP.toShort())
        out.putShort(8)
        out.putInt(total)
        ids.forEach { out.putInt(it) }
        return out.array()
    }

    /** AOSP pads the string data so each entry starts 4-byte aligned. */
    private fun paddingFor(length: Int): Int = (4 - (length % 4)) % 4

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream()
        writeLength8(out, value.length)
        writeLength8(out, bytes.size)
        out.write(bytes)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeLength8(out: java.io.ByteArrayOutputStream, length: Int) {
        if (length > 0x7F) {
            out.write(((length shr 8) and 0x7F) or 0x80)
            out.write(length and 0xFF)
        } else {
            out.write(length)
        }
    }

    private fun encodeUtf16(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_16LE)
        val out = java.io.ByteArrayOutputStream()
        val units = bytes.size / 2
        if (units > 0x7FFF) {
            out.write(((units shr 16) and 0x7FFF) or 0x8000)
            out.write((units shr 8) and 0xFF)
            out.write(units and 0xFF)
            out.write(0)
        } else {
            out.write(units and 0xFF)
            out.write((units shr 8) and 0xFF)
        }
        out.write(bytes)
        out.write(0)
        out.write(0)
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- byte helpers

    private fun slice(source: ByteArray, offset: Int, size: Int): ByteArray = source.copyOfRange(offset, offset + size)

    private fun chunkType(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private fun chunkSize(source: ByteArray, offset: Int): Int = u32At(source, offset + 4)

    private fun u32At(source: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(source, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value shr 8).toByte()
        target[offset + 2] = (value shr 16).toByte()
        target[offset + 3] = (value shr 24).toByte()
    }
}
