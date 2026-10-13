package com.apkanatomy.domain

enum class ComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }
data class AppEntry(val packageName: String, val label: String, val system: Boolean)
data class AppComponent(
    val name: String, val kind: ComponentKind, val exported: Boolean,
    val enabled: Boolean, val permission: String?, val authority: String? = null,
    val writePermission: String? = null,
)
data class AppPermission(val name: String, val protection: String)
data class AnatomyReport(
    val packageName: String, val label: String, val version: String,
    val minSdk: Int, val targetSdk: Int, val source: String,
    val components: List<AppComponent>, val permissions: List<AppPermission>,
    val structure: PackageStructure = PackageStructure(),
    val code: CodeIndex = CodeIndex(),
)
fun List<AppComponent>.filtered(query: String, exportedOnly: Boolean): List<AppComponent> =
    filter { (!exportedOnly || it.exported) && it.name.contains(query, ignoreCase = true) }
