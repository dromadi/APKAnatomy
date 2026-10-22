package com.apkanatomy.data

import com.apkanatomy.axml.ResourceTable
import com.apkanatomy.axml.XmlElement
import com.apkanatomy.domain.BackupRule
import com.apkanatomy.domain.Capabilities
import com.apkanatomy.domain.FeatureUse
import com.apkanatomy.domain.ForegroundServiceUse
import com.apkanatomy.domain.QueriesDecl
import com.apkanatomy.domain.TaskAffinityUse
import com.apkanatomy.domain.foregroundTypes
import java.io.File

/** Reads device features, package visibility, foreground services and other platform requests from the manifest. */
internal object CapabilitiesReader {
    fun read(root: XmlElement, apk: File, table: ResourceTable?): Capabilities {
        val packageName = root.attribute("package", namespaceUri = null).orEmpty()
        val app = root.childElements("application").firstOrNull()
        val notes = mutableListOf<String>()
        val features = root.childElements("uses-feature")
        val gl = features.mapNotNull { it.android("glEsVersion") }.mapNotNull(::glVersion).maxByOrNull { it.first }?.second
        val queries = root.childElements("queries").let { blocks ->
            QueriesDecl(
                packages = blocks.flatMap { it.childElements("package") }.mapNotNull { it.android("name") },
                intents = blocks.flatMap { it.childElements("intent") }.map { intent ->
                    (intent.childElements("action").mapNotNull { it.android("name")?.removePrefix("android.intent.action.") } +
                        intent.childElements("data").flatMap { d -> listOfNotNull(d.android("scheme")?.let { "$it:" }, d.android("mimeType")) }).joinToString(" ")
                },
                providers = blocks.flatMap { it.childElements("provider") }.mapNotNull { it.android("authorities") },
            )
        }
        val activities = app?.childElements("activity").orEmpty() + app?.childElements("activity-alias").orEmpty()
        val backupRules = mutableListOf<BackupRule>(); val ruleFiles = mutableListOf<String>()
        for ((attribute, what) in listOf("dataExtractionRules" to "aturan backup", "fullBackupContent" to "aturan full backup")) {
            val id = app?.androidReference(attribute) ?: continue
            val (xml, note) = ResourceReader.xmlResource(apk, id, table, what)
            note?.let(notes::add)
            val (path, rules) = xml ?: continue
            ruleFiles += path
            backupRules += backupRules(rules)
        }
        return Capabilities(
            features = features.mapNotNull { f -> f.android("name")?.let { FeatureUse(it, f.android("required") != "false") } },
            glEsVersion = gl,
            queries = queries,
            libraries = app?.childElements("uses-library").orEmpty().mapNotNull { l -> l.android("name")?.let { it to (l.android("required") != "false") } },
            foregroundServices = app?.childElements("service").orEmpty().mapNotNull { s ->
                val flags = s.android("foregroundServiceType")?.let(::parseFlags) ?: return@mapNotNull null
                ForegroundServiceUse(qualify(packageName, s.android("name") ?: return@mapNotNull null), foregroundTypes(flags))
            },
            sharedUserId = root.android("sharedUserId"),
            requestLegacyExternalStorage = app?.android("requestLegacyExternalStorage") == "true",
            backupRuleFiles = ruleFiles,
            backupRules = backupRules,
            foreignTaskAffinities = activities.mapNotNull { a ->
                val affinity = a.android("taskAffinity") ?: return@mapNotNull null
                // An empty affinity isolates the activity; the package (or a sub-name of it) is the default family.
                if (affinity.isEmpty() || affinity == packageName || affinity.startsWith("$packageName.") || affinity.startsWith(":")) return@mapNotNull null
                TaskAffinityUse(qualify(packageName, a.android("name") ?: "?"), affinity, a.android("allowTaskReparenting") == "true", a.android("exported") == "true")
            },
            grantUriProviders = app?.childElements("provider").orEmpty().filter { it.android("grantUriPermissions") == "true" }
                .mapNotNull { p -> p.android("name")?.let { qualify(packageName, it) } },
            notes = notes,
        )
    }

    /** "0x00030001" → (0x30001, "3.1"). */
    private fun glVersion(value: String): Pair<Int, String>? =
        parseFlags(value)?.takeIf { it > 0 }?.let { it to "${it ushr 16}.${it and 0xffff}" }

    private fun parseFlags(value: String): Int? =
        if (value.startsWith("0x")) value.removePrefix("0x").toLongOrNull(16)?.toInt() else value.toIntOrNull()

    private fun backupRules(root: XmlElement): List<BackupRule> {
        fun rules(section: String, e: XmlElement) = e.children.filterIsInstance<XmlElement>().filter { it.name == "include" || it.name == "exclude" }.map {
            BackupRule(section, it.name == "include", it.attribute("domain", namespaceUri = null) ?: "?", it.attribute("path", namespaceUri = null) ?: ".")
        }
        return when (root.name) {
            "data-extraction-rules" -> listOf("cloud-backup", "device-transfer").flatMap { s -> root.childElements(s).flatMap { rules(s, it) } }
            "full-backup-content" -> rules("full-backup-content", root)
            else -> emptyList()
        }
    }
}
