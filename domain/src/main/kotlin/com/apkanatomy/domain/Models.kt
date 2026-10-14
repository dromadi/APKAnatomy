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
    /** Base APK manifest decoded from binary XML; null when unreadable, with the reason in [manifestNote]. */
    val manifestXml: String? = null,
    val manifestNote: String? = null,
)
/**
 * Enabled exported components that any app can reach without holding a permission.
 * Providers count when either read or write access is unguarded. Expected for a launcher
 * activity, so this is a list to review, not a list of vulnerabilities.
 */
fun List<AppComponent>.openToAllApps(): List<AppComponent> = filter {
    it.exported && it.enabled && (it.permission.isNullOrEmpty() ||
        (it.kind == ComponentKind.PROVIDER && it.writePermission.isNullOrEmpty()))
}
fun List<AppComponent>.filtered(query: String, exportedOnly: Boolean): List<AppComponent> =
    filter { (!exportedOnly || it.exported) && it.name.contains(query, ignoreCase = true) }
