package com.apkanatomy.domain

/**
 * One match of a report-wide search. [tab] is where it lives; [methodId] or [classId] point into the code
 * index when the match is code, so the UI can open it directly.
 */
data class SearchHit(val tab: String, val title: String, val detail: String, val methodId: String? = null, val classId: String? = null)

/** Matches per section, in the order of the report's tabs, at most [perSection] each; empty for queries under two characters. */
fun AnatomyReport.searchAll(query: String, perSection: Int = 30): List<Pair<String, List<SearchHit>>> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    fun String?.has() = this != null && contains(q, ignoreCase = true)
    val sections = linkedMapOf<String, Sequence<SearchHit>>()

    sections["Privasi"] = privacyProfile().let { p ->
        p.trackers.asSequence().filter { it.name.has() || it.purpose.has() }.map { SearchHit("Privasi", it.name, "Pelacak · ${it.purpose}") } +
            p.dataAccess.asSequence().filter { it.category.has() || it.permissions.any { x -> x.has() } }.map { SearchHit("Privasi", it.category, it.permissions.joinToString()) }
    }
    sections["Perilaku"] = behaviors.findings.asSequence().filter { it.title.has() || it.category.has() || it.apis.any { a -> a.has() } || it.lesson.has() }
        .map { SearchHit("Perilaku", it.title, "${it.category} · ${it.apis.joinToString()}", methodId = it.usedBy.firstOrNull()) }
    sections["Teknologi"] = structure.technologies.asSequence().filter { it.name.has() || it.explanation.has() }.map { SearchHit("Teknologi", it.name, it.explanation) } +
        sdks.asSequence().filter { it.name.has() || it.category.has() || it.evidence.any { e -> e.has() } }.map { SearchHit("Teknologi", it.name, "${it.category} · ${it.evidence.joinToString()}") } +
        structure.libraryVersions.asSequence().filter { (k, v) -> k.has() || v.has() }.map { (k, v) -> SearchHit("Teknologi", k, "versi $v") } +
        native.libraries.asSequence().filter { it.path.has() || it.needed.any { n -> n.has() } || it.jniMethods.any { j -> j.has() } }.map { SearchHit("Teknologi", it.path, "${it.abi} · library native") }
    sections["Komponen"] = components.asSequence().filter { c ->
        c.name.has() || c.permission.has() || c.authority.has() || c.intentFilters.any { f -> (f.actions + f.categories + f.schemes + f.hosts + f.paths + f.mimeTypes).any { it.has() } }
    }.map { SearchHit("Komponen", it.name, "${it.kind.name.lowercase()}" + if (it.exported) " · exported" else "") }
    sections["Izin"] = permissions.asSequence().filter { it.name.has() }.map { SearchHit("Izin", it.name, it.protection) }
    sections["Kemampuan"] = capabilities.features.asSequence().filter { it.name.has() }.map { SearchHit("Kemampuan", it.name, if (it.required) "fitur wajib" else "fitur opsional") } +
        capabilities.foregroundServices.asSequence().filter { s -> s.service.has() || s.types.any { it.has() } }.map { SearchHit("Kemampuan", it.service, "foreground service · ${it.types.joinToString()}") } +
        (capabilities.queries.packages + capabilities.queries.intents + capabilities.queries.providers).asSequence().filter { it.has() }.map { SearchHit("Kemampuan", it, "queries") }
    sections["Kode"] = code.classes.asSequence().filter { it.displayName.has() }.map { SearchHit("Kode", it.displayName, "class · ${it.archive}/${it.dex}", classId = it.id) } +
        code.methods.asSequence().filter { it.name.has() || it.descriptor.has() }.map { SearchHit("Kode", prettyMethod(it.id), "method · ${it.descriptor}", methodId = it.id) } +
        code.strings.asSequence().filter { it.value.has() }.map { SearchHit("Kode", it.value.take(120), "string · dimuat oleh ${prettyMethod(it.methodId)}", methodId = it.methodId) }
    sections["Manifest"] = manifestXml.orEmpty().lineSequence().withIndex().filter { (_, line) -> line.has() }
        .map { (i, line) -> SearchHit("Manifest", line.trim().take(160), "baris ${i + 1}") }
    sections["DNA"] = notes(System.currentTimeMillis()).asSequence().filter { it.title.has() || it.explanation.has() }.map { SearchHit("DNA", it.title, it.explanation.take(160)) } +
        signing.certificates.asSequence().filter { it.subject.has() || it.issuer.has() || it.sha256.has() }.map { SearchHit("DNA", it.subject, "sertifikat · SHA-256 ${it.sha256.take(16)}…") }
    sections["Jejak"] = traces.hosts.asSequence().filter { h -> h.host.has() || h.service.has() || h.examples.any { it.has() } }
        .map { SearchHit("Jejak", it.host, it.service ?: it.examples.firstOrNull().orEmpty(), methodId = it.usedBy.firstOrNull()) } +
        traces.secrets.asSequence().filter { it.type.has() || it.masked.has() }.map { SearchHit("Jejak", "${it.type}: ${it.masked}", it.locations.joinToString(), methodId = it.usedBy.firstOrNull()) }
    sections["Isi APK"] = structure.entries.asSequence().filter { it.path.has() }.map { SearchHit("Isi APK", it.path, "${it.archive} · ${it.size} B") }

    return sections.mapNotNull { (name, hits) -> hits.take(perSection).toList().takeIf { it.isNotEmpty() }?.let { name to it } }
}
