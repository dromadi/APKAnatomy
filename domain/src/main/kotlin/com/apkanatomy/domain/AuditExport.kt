package com.apkanatomy.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The audit as CSV for a spreadsheet: one row per app, highest points first. Labels and names come from the
 * apps themselves, so every cell is quoted and a cell that a spreadsheet would run as a formula gets a leading '.
 */
fun List<AppAudit>.toAuditCsv(): String = buildString {
    append('﻿') // lets Excel read the file as UTF-8
    val header = listOf("Aplikasi", "Package", "Versi", "Sistem", "targetSdk", "Poin", "Debuggable", "allowBackup", "HTTP tanpa enkripsi",
        "Sumber pemasangan", "Komponen terbuka", "Akses data", "Pelacak", "Izin", "Alasan", "Catatan")
    appendCsvRow(header)
    ranked().forEach { (a, reasons) ->
        appendCsvRow(listOf(a.label, a.packageName, a.versionName, yesNo(a.system), a.targetSdk.toString(), reasons.sumOf { it.points }.toString(),
            yesNo(a.debuggable), yesNo(a.allowBackup), yesNo(a.cleartextTraffic), a.installer.orEmpty(), a.openComponents.toString(),
            dataCategories(a.permissions).joinToString("; "), a.trackers.joinToString("; ") { "${it.first} (${it.second})" },
            a.permissions.joinToString("; "), reasons.joinToString("; ") { it.text }, a.note.orEmpty()))
    }
}

/** A self-contained HTML page of the audit (and of [diff] when there is one), escaped and without scripts like the report export. */
fun List<AppAudit>.toAuditHtml(savedAt: Long, diff: AuditDiff? = null): String = buildString {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
    fun e(s: String) = escapeHtml(s)
    append("<!doctype html>\n<html lang=\"id\">\n<head>\n<meta charset=\"utf-8\">\n")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
    append("<title>APKAnatomy: audit aplikasi</title>\n<style>$REPORT_CSS</style>\n</head>\n<body>\n<main>\n")
    append("<header><div><h1>Audit aplikasi terpasang</h1><p class=\"muted\">${e("$size aplikasi · ${date.format(Date(savedAt))}")}</p></div></header>\n")
    append("<section class=\"cards\">")
    auditSummary().forEach { (name, count) -> append("<div class=\"card\"><b>$count</b><span>${e(name)}</span></div>") }
    append("</section>\n")
    append("<p class=\"muted\">Poin hanya mengurutkan daftar, bukan nilai keamanan: aplikasi peta wajar meminta lokasi.</p>\n")
    if (diff != null) {
        append("<h2>${e("Perubahan sejak ${date.format(Date(diff.since))}")}</h2>\n")
        if (diff.changes.isEmpty()) append("<p>Tidak ada aplikasi yang dipasang, dihapus, atau berubah.</p>\n")
        else {
            append("<ul>\n")
            diff.changes.forEach { c ->
                append("<li>${if (c.attention) "<strong>⚠ " else "<b>"}${e("${c.label} · ${c.kind.lowercase()}")}${if (c.attention) "</strong>" else "</b>"}")
                append(" <code>${e(c.packageName)}</code><br>${c.details.joinToString("<br>") { e(it) }}</li>\n")
            }
            append("</ul>\n")
        }
    }
    append("<h2>Aplikasi</h2>\n<div class=\"table\"><table>\n<tr><th>Aplikasi</th><th>Poin</th><th>Alasan</th></tr>\n")
    ranked().forEach { (a, reasons) ->
        append("<tr><td><b>${e(a.label)}</b><br><code>${e(a.packageName)}</code><br>${e("${a.versionName} · target ${a.targetSdk}")}</td>")
        append("<td>${reasons.sumOf { it.points }}</td><td>")
        append(if (reasons.isEmpty()) "Tidak ada yang menonjol." else reasons.joinToString("<br>") { e("• ${it.text}") })
        a.note?.let { append("<br><span class=\"muted\">${e(it)}</span>") }
        append("</td></tr>\n")
    }
    append("</table></div>\n</main>\n</body>\n</html>\n")
}

private fun List<AppAudit>.ranked() = map { it to it.reasons() }.sortedWith(compareByDescending<Pair<AppAudit, List<AuditReason>>> { (_, r) -> r.sumOf { it.points } }.thenBy { it.first.label.lowercase() })

private fun yesNo(b: Boolean) = if (b) "ya" else "tidak"

private fun StringBuilder.appendCsvRow(cells: List<String>) {
    cells.joinTo(this, ",") { raw ->
        val cell = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        "\"" + cell.replace("\"", "\"\"") + "\""
    }
    append("\r\n")
}
