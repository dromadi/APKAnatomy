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

    /**
     * @param resources resolves resource IDs to names (`@string/app_name` instead of `@0x7f060000`).
     * @throws AxmlException if [bytes] is not well-formed binary XML.
     */
    fun decode(bytes: ByteArray, resources: ResourceTable? = null): XmlElement =
        try {
            Parser(bytes, resources).parse()
        } catch (e: AxmlException) {
            throw e
        } catch (e: RuntimeException) {
            // Out-of-bounds reads from truncated or tampered files.
            throw AxmlException("Malformed binary XML: ${e.message}", e)
        }
}

private const val RES_XML_TYPE = 0x0003
private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
private const val RES_XML_START_ELEMENT_TYPE = 0x0102
private const val RES_XML_END_ELEMENT_TYPE = 0x0103
private const val RES_XML_CDATA_TYPE = 0x0104
private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180


private class ElementBuilder(
    val namespaceUri: String?,
    val name: String,
    val attributes: List<XmlAttribute>,
    val namespaceDeclarations: Map<String, String>,
) {
    val children = mutableListOf<XmlNode>()
    fun build() = XmlElement(namespaceUri, name, attributes, children.toList(), namespaceDeclarations)
}

private class Parser(private val bytes: ByteArray, private val resources: ResourceTable?) {
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
                RES_STRING_POOL_TYPE -> strings = readStringPool(buf, bytes, pos)
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
            val dataType = bytes[a + 15].toInt() and 0xff
            val data = buf.getInt(a + 16)
            XmlAttribute(
                namespaceUri = string(buf.getInt(a)),
                name = attributeName,
                value = formatValue(rawValue = buf.getInt(a + 8), dataType = dataType, data = data),
                resourceId = resourceId,
                referenceId = data.takeIf { (dataType == TYPE_REFERENCE || dataType == TYPE_DYNAMIC_REFERENCE) && it != 0 },
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

    private fun string(index: Int): String? = if (index == NO_INDEX) null else strings.getOrNull(index)

    private fun u16(offset: Int): Int = buf.u16(offset)

    private fun formatValue(rawValue: Int, dataType: Int, data: Int): String =
        string(rawValue) ?: formatTypedValue(dataType, data, ::string) { resources?.name(it) }
}
