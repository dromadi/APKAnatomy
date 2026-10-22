package com.apkanatomy.domain

enum class ChangeKind(val symbol: String) { DITAMBAH("+"), DIHAPUS("−"), BERUBAH("~") }

/** One difference; [attention] marks changes worth a closer look (a new sensitive permission, a new signer...). */
data class Change(val kind: ChangeKind, val text: String, val attention: Boolean = false)
data class DiffSection(val title: String, val changes: List<Change>)

/** A report without the heavy parts (code listing, raw manifest), small enough to keep as a comparison baseline. */
fun AnatomyReport.asBaseline(): AnatomyReport = copy(
    code = CodeIndex(packages = code.packages, definedClasses = code.definedClasses, shortNamedClasses = code.shortNamedClasses),
    manifestXml = null,
)

private fun <T> setDiff(old: Collection<T>, new: Collection<T>, describe: (T) -> String, attention: (T) -> Boolean = { false }): List<Change> =
    (new.toSet() - old.toSet()).map { Change(ChangeKind.DITAMBAH, describe(it), attention(it)) }.sortedBy { it.text } +
        (old.toSet() - new.toSet()).map { Change(ChangeKind.DIHAPUS, describe(it)) }.sortedBy { it.text }

private fun changed(label: String, old: Any?, new: Any?, attention: Boolean = false): Change? =
    if (old == new) null else Change(ChangeKind.BERUBAH, "$label: $old → $new", attention)

/** What changed from [old] to [new]; sections without changes are left out. */
fun compareReports(old: AnatomyReport, new: AnatomyReport, now: Long): List<DiffSection> = listOf(
    DiffSection("Identitas", listOfNotNull(
        changed("Package", old.packageName, new.packageName, attention = true),
        changed("Nama", old.label, new.label),
        changed("Versi", old.version, new.version),
        changed("minSdk", old.minSdk, new.minSdk),
        changed("targetSdk", old.targetSdk, new.targetSdk),
    )),
    DiffSection("DNA", buildList {
        val oldCerts = old.signing.certificates.associateBy { it.sha256 }
        val newCerts = new.signing.certificates.associateBy { it.sha256 }
        if (oldCerts.isNotEmpty() && newCerts.isNotEmpty() && oldCerts.keys.none { it in newCerts }) add(Change(ChangeKind.BERUBAH,
            "Sertifikat penandatangan berbeda sama sekali. Android menolak memasang versi ini di atas versi lama (kecuali lewat rotasi kunci v3); bisa jadi build pihak lain.", attention = true))
        addAll(setDiff(oldCerts.keys, newCerts.keys, { sha -> "Sertifikat ${(newCerts[sha] ?: oldCerts[sha])!!.subject} (SHA-256 ${sha.take(16)}…)" }))
        addAll(setDiff(old.signing.schemes, new.signing.schemes, { "Skema ${it.label}" }))
        changed("Rotasi kunci", old.signing.keyRotation, new.signing.keyRotation)?.let(::add)
        addAll(setDiff(old.notes(now).map { it.title }, new.notes(now).map { it.title }, { "Catatan: $it" },
            attention = { title -> new.notes(now).any { it.title == title && it.level == NoteLevel.PERHATIAN } }))
    }),
    DiffSection("Izin", setDiff(old.permissions.map { it.name }, new.permissions.map { it.name },
        { name -> name + (sensitivePermissionLesson(name)?.let { " ⚠ $it" } ?: "") }, attention = { sensitivePermissionLesson(it) != null })),
    DiffSection("Komponen", buildList {
        val oldByName = old.components.associateBy { it.kind to it.name }
        val newByName = new.components.associateBy { it.kind to it.name }
        val newOpen = new.components.openToAllApps().toSet()
        addAll(setDiff(oldByName.keys, newByName.keys, { (kind, name) -> "${kind.name.lowercase()} $name" },
            attention = { key -> newByName[key] in newOpen }))
        for (key in oldByName.keys intersect newByName.keys) {
            val (o, n) = oldByName.getValue(key) to newByName.getValue(key)
            changed("${key.first.name.lowercase()} ${key.second} exported", o.exported, n.exported, attention = n in newOpen)?.let(::add)
            changed("${key.first.name.lowercase()} ${key.second} izin", o.permission, n.permission, attention = n in newOpen)?.let(::add)
        }
        val links = { r: AnatomyReport -> r.components.flatMap { c -> c.intentFilters.filter { it.isDeepLink }.flatMap { f -> f.schemes.flatMap { s -> f.hosts.ifEmpty { listOf("") }.map { h -> "$s://$h" } } } }.toSet() }
        addAll(setDiff(links(old), links(new), { "Deep link $it" }))
    }),
    DiffSection("SDK & library", buildList {
        addAll(setDiff(old.sdks.map { it.name }, new.sdks.map { it.name }, { name -> name + ((new.sdks + old.sdks).first { it.name == name }.category.let { " ($it)" }) },
            attention = { name -> new.sdks.firstOrNull { it.name == name }?.category in setOf("Iklan", "Analitik & pemantauan") }))
        val ov = old.structure.libraryVersions; val nv = new.structure.libraryVersions
        (ov.keys intersect nv.keys).sorted().mapNotNullTo(this) { changed(it, ov[it], nv[it]) }
        addAll(setDiff(ov.keys, nv.keys, { it }))
    }),
    DiffSection("Jejak", buildList {
        addAll(setDiff(old.traces.hosts.map { it.host }, new.traces.hosts.map { it.host }, { "Host $it" }))
        addAll(setDiff(old.traces.secrets.map { "${it.type} ${it.masked}" }, new.traces.secrets.map { "${it.type} ${it.masked}" }, { it },
            attention = { s -> new.traces.secrets.any { "${it.type} ${it.masked}" == s && it.level == NoteLevel.PERHATIAN } }))
    }),
    DiffSection("Kulit & ukuran", buildList {
        val os = sizeBreakdown(old.structure.entries).associateBy { it.category }
        val ns = sizeBreakdown(new.structure.entries).associateBy { it.category }
        fun mib(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
        val oldTotal = os.values.sumOf { it.size }; val newTotal = ns.values.sumOf { it.size }
        if (oldTotal != newTotal) add(Change(ChangeKind.BERUBAH, "Total: ${mib(oldTotal)} → ${mib(newTotal)}"))
        for (category in SizeCategory.entries) {
            val o = os[category]?.size ?: 0; val n = ns[category]?.size ?: 0
            // Ignore sub-1% wiggle; it is noise for someone reading an update.
            if (o != n && kotlin.math.abs(n - o) * 100 > maxOf(o, n)) add(Change(ChangeKind.BERUBAH, "${category.label}: ${mib(o)} → ${mib(n)}"))
        }
        addAll(setDiff(old.structure.libraries.map { it.abi }.toSet(), new.structure.libraries.map { it.abi }.toSet(), { "ABI $it" }))
        changed("Kesiapan 16 KB", old.native.pageSizeNote()?.title, new.native.pageSizeNote()?.title,
            attention = new.native.pageSizeNote()?.level == NoteLevel.PERHATIAN)?.let(::add)
    }),
).filter { it.changes.isNotEmpty() }

fun diffToMarkdown(old: AnatomyReport, new: AnatomyReport, sections: List<DiffSection>): String = buildString {
    appendLine("# Perbandingan APKAnatomy")
    appendLine()
    appendLine("Lama: ${old.label} ${old.version} (${old.source}) → Baru: ${new.label} ${new.version} (${new.source})")
    if (sections.isEmpty()) appendLine("\nTidak ada perbedaan yang terdeteksi.")
    sections.forEach { section ->
        appendLine()
        appendLine("## ${section.title}")
        section.changes.forEach { appendLine("- ${it.kind.symbol} ${it.text}" + if (it.attention) " **(perhatikan)**" else "") }
    }
}
