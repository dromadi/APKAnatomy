package com.apkanatomy.axml

/** A node of a decoded XML document. */
sealed interface XmlNode

data class XmlText(val text: String) : XmlNode

data class XmlAttribute(
    val namespaceUri: String?,
    val name: String,
    /** Value rendered the way it would appear in source XML, e.g. `true`, `@0x7f080001`, `16dip`. */
    val value: String,
    /** Android resource id of the attribute (e.g. `0x01010003` for `android:name`), if known. */
    val resourceId: Int?,
    /** The referenced resource when the value is a reference (`@...`). */
    val referenceId: Int? = null,
)

data class XmlElement(
    val namespaceUri: String?,
    val name: String,
    val attributes: List<XmlAttribute>,
    val children: List<XmlNode>,
    /** Namespaces declared on this element, as prefix to URI. */
    val namespaceDeclarations: Map<String, String> = emptyMap(),
) : XmlNode {

    fun attribute(name: String, namespaceUri: String? = AxmlDecoder.ANDROID_NAMESPACE): String? =
        attributes.firstOrNull { it.name == name && it.namespaceUri == namespaceUri }?.value

    fun childElements(name: String): List<XmlElement> =
        children.filterIsInstance<XmlElement>().filter { it.name == name }

    /** Renders this element as indented source XML, one attribute per line. */
    fun toXmlString(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        appendElement(this@XmlElement, depth = 0, prefixes = emptyMap())
    }
}

private const val INDENT = "    "

private fun StringBuilder.appendElement(element: XmlElement, depth: Int, prefixes: Map<String, String>) {
    val scope = prefixes + element.namespaceDeclarations.entries.associate { (prefix, uri) -> uri to prefix }
    val pad = INDENT.repeat(depth)
    val attributeLines = element.namespaceDeclarations.map { (prefix, uri) ->
        val qualified = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
        "$qualified=\"${escape(uri)}\""
    } + element.attributes.map { "${qualifiedName(it.namespaceUri, it.name, scope)}=\"${escape(it.value)}\"" }

    val tag = qualifiedName(element.namespaceUri, element.name, scope)
    append(pad).append('<').append(tag)
    attributeLines.forEach { append('\n').append(pad).append(INDENT).append(it) }

    if (element.children.isEmpty()) {
        append(" />\n")
        return
    }
    append(">\n")
    for (child in element.children) {
        when (child) {
            is XmlElement -> appendElement(child, depth + 1, scope)
            is XmlText -> append(pad).append(INDENT).append(escape(child.text)).append('\n')
        }
    }
    append(pad).append("</").append(tag).append(">\n")
}

private fun qualifiedName(namespaceUri: String?, name: String, prefixes: Map<String, String>): String {
    if (namespaceUri.isNullOrEmpty()) return name
    val prefix = prefixes[namespaceUri]
        ?: if (namespaceUri == AxmlDecoder.ANDROID_NAMESPACE) "android" else return name
    return if (prefix.isEmpty()) name else "$prefix:$name"
}

private fun escape(value: String): String = buildString(value.length) {
    for (c in value) {
        when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c < ' ' -> append("&#").append(c.code).append(';')
            else -> append(c)
        }
    }
}
