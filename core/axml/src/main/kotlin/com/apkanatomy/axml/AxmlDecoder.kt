package com.apkanatomy.axml

import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Decodes Android binary XML (AXML), the compiled format used for `AndroidManifest.xml`
 * and `res/layout` files inside an APK.
 *
 * Format reference: `ResourceTypes.h` in AOSP (`ResChunk_header`, `ResStringPool_header`,
 * `ResXMLTree_node`, `ResXMLTree_attrExt`, `Res_value`).
 */
object AxmlDecoder {

    const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

    /** @throws AxmlException if [bytes] is not well-formed binary XML. */
    fun decode(bytes: ByteArray): XmlElement =
        try {
            Parser(bytes).parse()
        } catch (e: AxmlException) {
            throw e
        } catch (e: RuntimeException) {
            // Out-of-bounds reads from truncated or tampered files.
            throw AxmlException("Malformed binary XML: ${e.message}", e)
        }
}

private const val RES_STRING_POOL_TYPE = 0x0001
private const val RES_XML_TYPE = 0x0003
private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
private const val RES_XML_START_ELEMENT_TYPE = 0x0102
private const val RES_XML_END_ELEMENT_TYPE = 0x0103
private const val RES_XML_CDATA_TYPE = 0x0104
private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180

private const val UTF8_FLAG = 1 shl 8
private const val NO_INDEX = -1

private class ElementBuilder(
    val namespaceUri: String?,
    val name: String,
    val attributes: List<XmlAttribute>,
    val namespaceDeclarations: Map<String, String>,
) {
    val children = mutableListOf<XmlNode>()
    fun build() = XmlElement(namespaceUri, name, attributes, children.toList(), namespaceDeclarations)
}

private class Parser(private val bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private var strings: List<String> = emptyList()
    private var resourceIds = IntArray(0)
    private val pendingNamespaces = LinkedHashMap<String, String>()
    private val stack = ArrayDeque<ElementBuilder>()
    private var root: XmlElement? = null

    fun parse(): XmlElement {
        if (bytes.size < 8) throw AxmlException("File too small to be binary XML")
        val type = u16(0)
        if (type != RES_XML_TYPE) throw AxmlException("Not binary XML (chunk type 0x%04x)".format(type))
        val end = minOf(buf.getInt(4).toLong() and 0xffffffffL, bytes.size.toLong())

        var pos = u16(2)
        while (pos + 8 <= end) {
            val chunkType = u16(pos)
            val headerSize = u16(pos + 2)
            val chunkSize = buf.getInt(pos + 4)
            if (chunkSize < 8 || headerSize > chunkSize || pos.toLong() + chunkSize > end) {
                throw AxmlException("Invalid chunk 0x%04x at offset %d".format(chunkType, pos))
            }
            val body = pos + headerSize
            when (chunkType) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(pos)
                RES_XML_RESOURCE_MAP_TYPE ->
                    resourceIds = IntArray((chunkSize - headerSize) / 4) { buf.getInt(body + it * 4) }
                RES_XML_START_NAMESPACE_TYPE ->
                    pendingNamespaces[string(buf.getInt(body)).orEmpty()] = string(buf.getInt(body + 4)).orEmpty()
                RES_XML_START_ELEMENT_TYPE -> startElement(body)
                RES_XML_END_ELEMENT_TYPE -> endElement()
                RES_XML_CDATA_TYPE -> stack.lastOrNull()?.children?.add(XmlText(string(buf.getInt(body)).orEmpty()))
                // END_NAMESPACE and unknown chunk types carry nothing we render.
            }
            pos += chunkSize
        }
        if (stack.isNotEmpty()) throw AxmlException("Unclosed element <${stack.last().name}>")
        return root ?: throw AxmlException("Binary XML has no root element")
    }

    private fun startElement(ext: Int) {
        val namespaceUri = string(buf.getInt(ext))
        val name = string(buf.getInt(ext + 4)) ?: throw AxmlException("Element without a name")
        val attributeStart = u16(ext + 8)
        val attributeSize = u16(ext + 10)
        val attributeCount = u16(ext + 12)

        val attributes = List(attributeCount) { i ->
            val a = ext + attributeStart + i * attributeSize
            val nameIndex = buf.getInt(a + 4)
            val resourceId = resourceIds.getOrNull(nameIndex)?.takeIf { it != 0 }
            val attributeName = string(nameIndex)?.takeIf { it.isNotEmpty() }
                ?: resourceId?.let { "attr_0x%08x".format(it) }
                ?: "attr$i"
            XmlAttribute(
                namespaceUri = string(buf.getInt(a)),
                name = attributeName,
                value = formatValue(
                    rawValue = buf.getInt(a + 8),
                    dataType = bytes[a + 15].toInt() and 0xff,
                    data = buf.getInt(a + 16),
                ),
                resourceId = resourceId,
            )
        }
        stack.addLast(ElementBuilder(namespaceUri, name, attributes, LinkedHashMap(pendingNamespaces)))
        pendingNamespaces.clear()
    }

    private fun endElement() {
        val element = stack.removeLastOrNull()?.build() ?: throw AxmlException("Unbalanced end element")
        val parent = stack.lastOrNull()
        when {
            parent != null -> parent.children += element
            root == null -> root = element
            else -> throw AxmlException("Multiple root elements")
        }
    }

    private fun readStringPool(start: Int): List<String> {
        val headerSize = u16(start + 2)
        val chunkSize = buf.getInt(start + 4)
        val count = buf.getInt(start + 8)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        if (count < 0 || headerSize + count.toLong() * 4 > chunkSize) {
            throw AxmlException("Invalid string pool ($count strings)")
        }
        val utf8 = flags and UTF8_FLAG != 0
        return List(count) { i ->
            val offset = start + stringsStart + buf.getInt(start + headerSize + i * 4)
            if (utf8) readUtf8(offset) else readUtf16(offset)
        }
    }

    private fun readUtf8(offset: Int): String {
        var p = offset
        // UTF-16 length first (unused), then the UTF-8 byte length; each takes 1 or 2 bytes.
        p += if (bytes[p].toInt() and 0x80 != 0) 2 else 1
        var length = bytes[p++].toInt() and 0xff
        if (length and 0x80 != 0) length = ((length and 0x7f) shl 8) or (bytes[p++].toInt() and 0xff)
        return String(bytes, p, length, Charsets.UTF_8)
    }

    private fun readUtf16(offset: Int): String {
        var p = offset
        var length = u16(p)
        p += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7fff) shl 16) or u16(p)
            p += 2
        }
        return String(bytes, p, length * 2, Charsets.UTF_16LE)
    }

    private fun string(index: Int): String? = if (index == NO_INDEX) null else strings.getOrNull(index)

    private fun u16(offset: Int): Int = buf.getShort(offset).toInt() and 0xffff

    private fun formatValue(rawValue: Int, dataType: Int, data: Int): String {
        string(rawValue)?.let { return it }
        return when (dataType) {
            TYPE_NULL -> ""
            TYPE_REFERENCE, TYPE_DYNAMIC_REFERENCE -> if (data == 0) "@null" else "@0x%08x".format(data)
            TYPE_ATTRIBUTE, TYPE_DYNAMIC_ATTRIBUTE -> "?0x%08x".format(data)
            TYPE_STRING -> string(data).orEmpty()
            TYPE_FLOAT -> formatNumber(Float.fromBits(data))
            TYPE_DIMENSION -> formatNumber(complexToFloat(data)) + DIMENSION_UNITS.getOrElse(data and 0xf) { "" }
            TYPE_FRACTION -> formatNumber(complexToFloat(data) * 100) + if (data and 0xf == 0) "%" else "%p"
            TYPE_INT_DEC -> data.toString()
            TYPE_INT_HEX -> "0x%x".format(data)
            TYPE_INT_BOOLEAN -> (data != 0).toString()
            in TYPE_FIRST_COLOR..TYPE_LAST_COLOR -> "#%08x".format(data)
            else -> "0x%08x".format(data)
        }
    }
}

// Res_value data types.
private const val TYPE_NULL = 0x00
private const val TYPE_REFERENCE = 0x01
private const val TYPE_ATTRIBUTE = 0x02
private const val TYPE_STRING = 0x03
private const val TYPE_FLOAT = 0x04
private const val TYPE_DIMENSION = 0x05
private const val TYPE_FRACTION = 0x06
private const val TYPE_DYNAMIC_REFERENCE = 0x07
private const val TYPE_DYNAMIC_ATTRIBUTE = 0x08
private const val TYPE_INT_DEC = 0x10
private const val TYPE_INT_HEX = 0x11
private const val TYPE_INT_BOOLEAN = 0x12
private const val TYPE_FIRST_COLOR = 0x1c
private const val TYPE_LAST_COLOR = 0x1f

private val DIMENSION_UNITS = listOf("px", "dip", "sp", "pt", "in", "mm")
private val RADIX_MULTIPLIERS = floatArrayOf(1f / (1 shl 8), 1f / (1 shl 15), 1f / (1 shl 23), 1f / (1L shl 31))

private fun complexToFloat(complex: Int): Float =
    (complex and 0xffffff00.toInt()) * RADIX_MULTIPLIERS[(complex shr 4) and 3]

private fun formatNumber(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else value.toString()
