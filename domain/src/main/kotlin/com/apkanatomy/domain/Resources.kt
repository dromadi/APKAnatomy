package com.apkanatomy.domain

/** One resource with its value in every configuration ("" is the default), e.g. ("in", "Halo"). */
data class ResourceItem(val id: Int, val name: String, val values: List<Pair<String, String>>) {
    val type: String get() = name.substringAfter(':').substringBefore('/')
    val idHex: String get() = "0x%08x".format(id)
}

data class ResourceListing(val items: List<ResourceItem>, val note: String? = null) {
    /** Types by number of resources, most first. */
    val types: List<String> get() = items.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
}

/** Resources of [type] (all when null) whose name, ID or any value contains [query]. */
fun List<ResourceItem>.filterBy(type: String?, query: String): List<ResourceItem> {
    val q = query.trim()
    return filter { item ->
        (type == null || item.type == type) &&
            (q.isEmpty() || item.name.contains(q, true) || item.idHex.contains(q, true) || item.values.any { it.second.contains(q, true) })
    }
}

/** "" → "default"; other qualifiers are shown as in res/ folder names, e.g. "in", "night", "sw600dp". */
fun describeConfig(config: String): String = config.ifEmpty { "default" }
