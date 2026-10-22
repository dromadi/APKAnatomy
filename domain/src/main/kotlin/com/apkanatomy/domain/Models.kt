package com.apkanatomy.domain

enum class ComponentKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER }
data class AppEntry(val packageName: String, val label: String, val system: Boolean) : java.io.Serializable
data class AppComponent(
    val name: String, val kind: ComponentKind, val exported: Boolean,
    val enabled: Boolean, val permission: String?, val authority: String? = null,
    val writePermission: String? = null,
    /** From the decoded manifest; empty when it could not be read. */
    val intentFilters: List<IntentFilterInfo> = emptyList(),
) : java.io.Serializable
/**
 * Android merges the attributes of all <data> elements in one filter (a scheme in one element
 * and a host in another still match together), so they are collected per filter.
 */
data class IntentFilterInfo(
    val actions: List<String>, val categories: List<String>,
    val schemes: List<String> = emptyList(),
    /** Host, with ":port" when declared. */
    val hosts: List<String> = emptyList(),
    /** e.g. "/open", "/open*" (prefix), "*.pdf" (suffix), "pola /.*" (pattern). */
    val paths: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
    val autoVerify: Boolean = false,
) : java.io.Serializable
data class AppPermission(val name: String, val protection: String) : java.io.Serializable
data class AnatomyReport(
    val packageName: String, val label: String, val version: String,
    val minSdk: Int, val targetSdk: Int, val source: String,
    val components: List<AppComponent>, val permissions: List<AppPermission>,
    val structure: PackageStructure = PackageStructure(),
    val code: CodeIndex = CodeIndex(),
    /** Base APK manifest decoded from binary XML; null when unreadable, with the reason in [manifestNote]. */
    val manifestXml: String? = null,
    val manifestNote: String? = null,
    val signing: SigningReport = SigningReport(),
    /** Null when the package manager did not expose application flags. */
    val security: SecurityFlags? = null,
    val sdks: List<DetectedSdk> = emptyList(),
    /** App icon rendered to PNG during analysis (an imported APK is deleted afterwards). */
    val iconPng: ByteArray? = null,
    val networkSecurity: NetworkSecurityConfig? = null,
    val resources: ResourceSummary? = null,
    val traces: TraceReport = TraceReport(),
    val native: NativeReport = NativeReport(),
    val capabilities: Capabilities = Capabilities(),
) : java.io.Serializable
data class ResourceSummary(val count: Int, val typeCounts: Map<String, Int>, val locales: List<String>, val note: String? = null) : java.io.Serializable
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
