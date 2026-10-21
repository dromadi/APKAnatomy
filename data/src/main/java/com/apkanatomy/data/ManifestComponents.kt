package com.apkanatomy.data

import com.apkanatomy.axml.XmlElement
import com.apkanatomy.domain.IntentFilterInfo

// Framework attribute IDs (verified with aapt2), used when obfuscation strips attribute names.
private val ATTRIBUTE_IDS = mapOf(
    "name" to 0x01010003, "autoVerify" to 0x010104ee, "scheme" to 0x01010027, "host" to 0x01010028,
    "port" to 0x01010029, "path" to 0x0101002a, "pathPrefix" to 0x0101002b, "pathPattern" to 0x0101002c,
    "pathSuffix" to 0x0101061e, "pathAdvancedPattern" to 0x01010620, "mimeType" to 0x01010026,
)
private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

private fun XmlElement.android(name: String): String? =
    attribute(name) ?: ATTRIBUTE_IDS[name]?.let { id -> attributes.firstOrNull { it.resourceId == id }?.value }

/** Intent-filters per fully qualified component name, as declared in the manifest. */
internal fun intentFiltersByComponent(manifest: XmlElement): Map<String, List<IntentFilterInfo>> {
    val packageName = manifest.attribute("package", namespaceUri = null).orEmpty()
    val application = manifest.childElements("application").firstOrNull() ?: return emptyMap()
    return application.children.filterIsInstance<XmlElement>()
        .filter { it.name in COMPONENT_TAGS }
        .mapNotNull { element ->
            val name = element.android("name") ?: return@mapNotNull null
            qualify(packageName, name) to element.childElements("intent-filter").map(::intentFilter)
        }
        .groupBy({ it.first }, { it.second }).mapValues { (_, filters) -> filters.flatten() }
}

/** Resolves manifest shorthand (".Main", "Main") the way the package manager does. */
internal fun qualify(packageName: String, name: String): String = when {
    name.startsWith(".") -> packageName + name
    '.' !in name -> "$packageName.$name"
    else -> name
}

private fun intentFilter(filter: XmlElement): IntentFilterInfo {
    val data = filter.childElements("data")
    fun all(attribute: String, format: (String) -> String = { it }) = data.mapNotNull { it.android(attribute)?.let(format) }.distinct()
    return IntentFilterInfo(
        actions = filter.childElements("action").mapNotNull { it.android("name") },
        categories = filter.childElements("category").mapNotNull { it.android("name") },
        schemes = all("scheme"),
        hosts = data.mapNotNull { d -> d.android("host")?.let { host -> d.android("port")?.let { "$host:$it" } ?: host } }.distinct(),
        paths = (all("path") + all("pathPrefix") { "$it*" } + all("pathSuffix") { "*$it" } +
            all("pathPattern") { "pola $it" } + all("pathAdvancedPattern") { "regex $it" }).distinct(),
        mimeTypes = all("mimeType"),
        autoVerify = filter.android("autoVerify") == "true",
    )
}
