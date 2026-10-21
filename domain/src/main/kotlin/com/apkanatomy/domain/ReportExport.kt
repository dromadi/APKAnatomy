package com.apkanatomy.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun date(millis: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))
private fun md(text: String) = text.replace("|", "\\|").replace("\n", " ")

/** Human-readable summary for sharing or archiving; excludes code listings and the raw manifest. */
fun AnatomyReport.toMarkdown(now: Long): String = buildString {
    appendLine("# Laporan APKAnatomy: $label")
    appendLine()
    appendLine("- Package: `$packageName`")
    appendLine("- Versi: $version · minSdk $minSdk · targetSdk $targetSdk")
    appendLine("- Sumber: $source · dibuat ${date(now)} (UTC)")
    appendLine()
    appendLine("## DNA & keamanan")
    appendLine("Skema tanda tangan: ${signing.schemes.joinToString { it.label }.ifEmpty { "tidak ditemukan" }}" +
        (if (signing.keyRotation) " · rotasi kunci" else ""))
    appendLine()
    signing.certificates.forEach { appendLine("- Sertifikat `${md(it.subject)}` · SHA-256 `${it.sha256}` · berlaku ${date(it.notBefore)} s.d. ${date(it.notAfter)} · ${it.foundIn.joinToString()}") }
    appendLine()
    notes(now).forEach { appendLine("- **${it.level.name}** ${md(it.title)}: ${md(it.explanation)}") }
    networkSecurity?.let { nsc ->
        appendLine()
        appendLine("Network Security Config `${nsc.file}`:")
        listOfNotNull(nsc.base?.let { "base-config" to it }, nsc.debugOverrides?.let { "debug-overrides" to it }).plus(nsc.domains.map { it.domains.joinToString() to it })
            .forEach { (name, scope) -> appendLine("- $name: ${describeScope(scope)}") }
    }
    appendLine()
    appendLine("## Kerangka")
    val open = components.openToAllApps().toSet()
    appendLine("${components.size} komponen, ${open.size} exported tanpa izin, ${permissions.size} izin.")
    appendLine()
    appendLine("| Komponen | Jenis | Exported | Izin | Intent-filter |")
    appendLine("|---|---|---|---|---|")
    components.forEach { c ->
        val filters = c.intentFilters.joinToString("; ") { f -> ((f.actions + f.categories).map { it.substringAfterLast('.') } + f.schemes.map { "$it:" } + f.hosts).joinToString(" ") }
        appendLine("| `${md(c.name)}` | ${c.kind.name.lowercase()} | ${if (c in open) "ya, terbuka" else if (c.exported) "ya" else "tidak"} | ${md(c.permission ?: "-")} | ${md(filters.ifEmpty { "-" })} |")
    }
    appendLine()
    appendLine("### Izin")
    permissions.sortedBy { sensitivePermissionLesson(it.name) == null }.forEach {
        appendLine("- `${it.name}` (${it.protection})" + (sensitivePermissionLesson(it.name)?.let { lesson -> " ⚠ $lesson" } ?: ""))
    }
    appendLine()
    appendLine("## Organ")
    appendLine("Teknologi: ${structure.technologies.joinToString { it.name }.ifEmpty { "tidak ada penanda yang dikenali" }}")
    appendLine()
    sdks.forEach { appendLine("- ${it.category}: **${it.name}**" + (if (it.versions.isNotEmpty()) " (${it.versions.joinToString()})" else "") + " · bukti ${it.evidence.joinToString()}") }
    appendLine()
    appendLine("DEX: ${structure.dex.size} file · library native: ${structure.libraries.map { it.abi }.distinct().joinToString().ifEmpty { "tidak ada" }}")
    appendLine()
    appendLine("## Kulit: ukuran")
    appendLine("| Kategori | File | Ukuran | Di ZIP |")
    appendLine("|---|---|---|---|")
    sizeBreakdown(structure.entries).forEach { appendLine("| ${it.category.label} | ${it.files} | ${it.size} B | ${it.compressedSize} B |") }
    val warnings = structure.warnings + signing.warnings + code.warnings + listOfNotNull(manifestNote)
    if (warnings.isNotEmpty()) {
        appendLine()
        appendLine("## Hasil sebagian")
        warnings.distinct().forEach { appendLine("- ${md(it)}") }
    }
    appendLine()
    appendLine("_Analisis statis oleh APKAnatomy. Temuan adalah bahan belajar, bukan vonis kerentanan._")
}

private fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
    is Boolean, is Int, is Long -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> json(k.toString()) + ":" + json(v) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { json(it) }
    else -> json(value.toString())
}

/** Machine-readable report with the same scope as [toMarkdown]. */
fun AnatomyReport.toJson(now: Long): String = json(mapOf(
    "tool" to "APKAnatomy", "generatedAt" to now,
    "app" to mapOf("package" to packageName, "label" to label, "version" to version, "minSdk" to minSdk, "targetSdk" to targetSdk, "source" to source),
    "signing" to mapOf("schemes" to signing.schemes.map { it.name }, "keyRotation" to signing.keyRotation,
        "certificates" to signing.certificates.map { mapOf("subject" to it.subject, "issuer" to it.issuer, "sha256" to it.sha256, "sha1" to it.sha1,
            "notBefore" to it.notBefore, "notAfter" to it.notAfter, "keyAlgorithm" to it.keyAlgorithm, "keySize" to it.keySize, "foundIn" to it.foundIn) },
        "otherBlocks" to signing.otherBlocks, "warnings" to signing.warnings),
    "security" to security?.let { mapOf("debuggable" to it.debuggable, "allowBackup" to it.allowBackup, "cleartextTraffic" to it.cleartextTraffic,
        "testOnly" to it.testOnly, "networkSecurityConfig" to it.networkSecurityConfig) },
    "networkSecurity" to networkSecurity?.let { nsc -> mapOf("file" to nsc.file, "base" to nsc.base?.let(::scopeJson),
        "domains" to nsc.domains.map(::scopeJson), "debugOverrides" to nsc.debugOverrides?.let(::scopeJson)) },
    "resources" to resources?.let { mapOf("count" to it.count, "types" to it.typeCounts, "locales" to it.locales) },
    "notes" to notes(now).map { mapOf("level" to it.level.name, "title" to it.title, "explanation" to it.explanation) },
    "components" to components.map { c -> mapOf("name" to c.name, "kind" to c.kind.name, "exported" to c.exported, "enabled" to c.enabled,
        "permission" to c.permission, "writePermission" to c.writePermission, "authority" to c.authority,
        "intentFilters" to c.intentFilters.map { f -> mapOf("actions" to f.actions, "categories" to f.categories, "schemes" to f.schemes,
            "hosts" to f.hosts, "paths" to f.paths, "mimeTypes" to f.mimeTypes, "autoVerify" to f.autoVerify) }) },
    "permissions" to permissions.map { mapOf("name" to it.name, "protection" to it.protection, "sensitive" to (sensitivePermissionLesson(it.name) != null)) },
    "technologies" to structure.technologies.map { it.name },
    "sdks" to sdks.map { mapOf("name" to it.name, "category" to it.category, "versions" to it.versions, "evidence" to it.evidence) },
    "libraryVersions" to structure.libraryVersions,
    "sizes" to sizeBreakdown(structure.entries).map { mapOf("category" to it.category.name, "files" to it.files, "size" to it.size, "compressedSize" to it.compressedSize) },
    "warnings" to (structure.warnings + signing.warnings + code.warnings + listOfNotNull(manifestNote)).distinct(),
))

/** One-line description of a Network Security Config scope. */
fun describeScope(scope: NscScope): String = listOfNotNull(
    scope.cleartextPermitted?.let { if (it) "cleartext diizinkan" else "cleartext dilarang" },
    scope.trustAnchors?.let { "CA: ${it.joinToString()}" },
    if (scope.pins > 0) "${scope.pins} pin" + (scope.pinExpiration?.let { " s.d. $it" } ?: "") else null,
    scope.certificateTransparency?.let { if (it) "CT aktif" else "CT nonaktif" },
).joinToString(" · ").ifEmpty { "mewarisi aturan di atasnya" }

private fun scopeJson(scope: NscScope) = mapOf("domains" to scope.domains, "cleartextPermitted" to scope.cleartextPermitted,
    "trustAnchors" to scope.trustAnchors, "pins" to scope.pins, "pinExpiration" to scope.pinExpiration,
    "certificateTransparency" to scope.certificateTransparency)
