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
    val privacy = privacyProfile()
    appendLine("## Privasi")
    appendLine(privacy.summary)
    privacy.trackers.forEach { appendLine("- Pelacak **${it.name}** (${it.purpose})") }
    privacy.dataAccess.forEach { appendLine("- Data: ${it.category} (${it.permissions.joinToString { p -> p.substringAfterLast('.') }})") }
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
    appendLine("## Kemampuan")
    capabilities.features.forEach { appendLine("- Fitur `${it.name}`" + if (it.required) " (wajib)" else " (opsional)") }
    capabilities.glEsVersion?.let { appendLine("- OpenGL ES $it") }
    capabilities.foregroundServices.forEach { appendLine("- Foreground service `${md(it.service)}`: ${it.types.joinToString()}") }
    if (!capabilities.queries.isEmpty) appendLine("- Queries: ${(capabilities.queries.packages + capabilities.queries.intents + capabilities.queries.providers).joinToString { md(it) }}")
    capabilities.backupRules.forEach { appendLine("- Backup ${it.section}: ${if (it.include) "sertakan" else "kecualikan"} ${it.domain}/${md(it.path)}") }
    appendLine()
    appendLine("## Perilaku")
    if (behaviors.findings.isEmpty()) appendLine("Tidak ada pemanggilan API sensitif yang dikenali.")
    behaviors.findings.forEach { f ->
        appendLine("- **${f.level.name}** ${md(f.category)}: ${md(f.title)} · ${f.count} tempat (${f.inAppPackage} di package aplikasi) · ${md(f.apis.joinToString())}" +
            (f.usedBy.firstOrNull()?.let { " · mis. `${md(prettyMethod(it))}`" } ?: ""))
    }
    appendLine()
    appendLine("## Library native")
    native.pageSizeNote()?.let { appendLine("**${it.level.name}** ${md(it.title)}: ${md(it.explanation)}") }
    native.libraries.forEach { lib ->
        appendLine("- `${lib.abi}/${lib.path.substringAfterLast('/')}` ${lib.machine ?: "?"} ${lib.bits ?: "?"}-bit · align ${lib.minLoadAlign ?: "?"}" +
            (if (lib.stored) " · tersimpan, offset ${lib.zipDataOffset}" else "") + (if (lib.needed.isNotEmpty()) " · butuh ${lib.needed.joinToString()}" else "") +
            (if (lib.jniMethods.isNotEmpty()) " · ${lib.jniMethods.size} JNI" else ""))
    }
    appendLine()
    appendLine("## Jejak")
    appendLine("${traces.hosts.size} host, ${traces.secrets.size} kandidat kunci/token (nilai tersamar).")
    appendLine()
    traces.secrets.forEach { appendLine("- **${it.level.name}** ${md(it.type)} `${md(it.masked)}` · ${md(it.locations.joinToString())}") }
    traces.hosts.forEach { appendLine("- ${md(it.host)}" + (it.service?.let { s -> " (${md(s)})" } ?: "") + (if (it.cleartext) " · http" else "") + (if (it.local) " · lokal" else "")) }
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
    "privacy" to privacyProfile().let { p -> mapOf("summary" to p.summary, "usesAdvertisingId" to p.usesAdvertisingId,
        "trackers" to p.trackers.map { mapOf("name" to it.name, "purpose" to it.purpose) },
        "dataAccess" to p.dataAccess.map { mapOf("category" to it.category, "permissions" to it.permissions) }, "trackerHosts" to p.trackerHosts) },
    "capabilities" to mapOf("features" to capabilities.features.map { mapOf("name" to it.name, "required" to it.required) },
        "glEsVersion" to capabilities.glEsVersion,
        "queries" to mapOf("packages" to capabilities.queries.packages, "intents" to capabilities.queries.intents, "providers" to capabilities.queries.providers),
        "libraries" to capabilities.libraries.map { mapOf("name" to it.first, "required" to it.second) },
        "foregroundServices" to capabilities.foregroundServices.map { mapOf("service" to it.service, "types" to it.types) },
        "sharedUserId" to capabilities.sharedUserId, "requestLegacyExternalStorage" to capabilities.requestLegacyExternalStorage,
        "backupRules" to capabilities.backupRules.map { mapOf("section" to it.section, "include" to it.include, "domain" to it.domain, "path" to it.path) },
        "foreignTaskAffinities" to capabilities.foreignTaskAffinities.map { mapOf("activity" to it.activity, "affinity" to it.affinity, "allowReparenting" to it.allowReparenting) },
        "grantUriProviders" to capabilities.grantUriProviders),
    "native" to mapOf("extractNativeLibs" to native.extractNativeLibs, "pageSize16k" to native.pageSizeNote()?.level?.name,
        "libraries" to native.libraries.map { mapOf("archive" to it.archive, "path" to it.path, "abi" to it.abi, "machine" to it.machine, "bits" to it.bits,
            "minLoadAlign" to it.minLoadAlign, "stored" to it.stored, "zipDataOffset" to it.zipDataOffset, "soname" to it.soname, "needed" to it.needed,
            "jniMethods" to it.jniMethods, "jniOnLoad" to it.hasJniOnLoad, "symbolTable" to it.hasSymbolTable, "debugInfo" to it.hasDebugInfo) }),
    "behaviors" to behaviors.findings.map { mapOf("id" to it.id, "category" to it.category, "title" to it.title, "level" to it.level.name,
        "apis" to it.apis, "count" to it.count, "inAppPackage" to it.inAppPackage, "examples" to it.usedBy.map(::prettyMethod)) },
    "traces" to mapOf(
        "hosts" to traces.hosts.map { mapOf("host" to it.host, "service" to it.service, "cleartext" to it.cleartext, "local" to it.local,
            "examples" to it.examples, "locations" to it.locations, "count" to it.count) },
        "secrets" to traces.secrets.map { mapOf("type" to it.type, "level" to it.level.name, "masked" to it.masked, "locations" to it.locations) }),
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
