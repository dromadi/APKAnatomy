package com.apkanatomy.domain

/**
 * A self-contained HTML page of the report, for opening in any browser. Every string from the APK is
 * untrusted, so all text is HTML-escaped and a Content-Security-Policy forbids scripts and remote loads.
 */
fun AnatomyReport.toHtml(now: Long): String {
    val attention = notes(now).count { it.level == NoteLevel.PERHATIAN }
    val cards = listOf(
        "Komponen" to components.size.toString(), "Izin" to permissions.size.toString(), "SDK" to sdks.size.toString(),
        "Perilaku" to behaviors.findings.size.toString(), "Host" to traces.hosts.size.toString(), "Perlu perhatian" to attention.toString(),
    )
    val icon = iconPng?.let { "<img class=\"icon\" alt=\"\" src=\"data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(it)}\">" }.orEmpty()
    return buildString {
        append("<!doctype html>\n<html lang=\"id\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'; img-src data:\">\n")
        append("<title>${escapeHtml("APKAnatomy: $label")}</title>\n<style>$REPORT_CSS</style>\n</head>\n<body>\n<main>\n")
        append("<header>$icon<div><h1>${escapeHtml(label)}</h1><p class=\"muted\">${escapeHtml("$packageName · versi $version · minSdk $minSdk · targetSdk $targetSdk")}</p></div></header>\n")
        append("<section class=\"cards\">")
        cards.forEach { (name, value) -> append("<div class=\"card${if (name == "Perlu perhatian" && attention > 0) " warn" else ""}\"><b>${escapeHtml(value)}</b><span>${escapeHtml(name)}</span></div>") }
        append("</section>\n")
        // The Markdown export already holds every section; its own title line is replaced by the header above.
        append(markdownToHtml(toMarkdown(now).lines().dropWhile { !it.startsWith("## ") }.joinToString("\n")))
        append("\n</main>\n</body>\n</html>\n")
    }
}

internal fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
        else -> append(c)
    }
}

/** Inline Markdown of the export: `code`, **bold** and _italic_, applied after escaping. */
private fun inline(text: String): String {
    var html = escapeHtml(text.replace("\\|", "|"))
    html = Regex("`([^`]+)`").replace(html) { "<code>${it.groupValues[1]}</code>" }
    html = Regex("\\*\\*([^*]+)\\*\\*").replace(html) { "<strong>${it.groupValues[1]}</strong>" }
    html = Regex("(?<![\\w])_([^_]+)_(?![\\w])").replace(html) { "<em>${it.groupValues[1]}</em>" }
    return html
}

/** The Markdown subset [toMarkdown] produces: headings, paragraphs, "- " lists and pipe tables. */
internal fun markdownToHtml(markdown: String): String = buildString {
    val lines = markdown.lines()
    var i = 0
    fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { it.trim() }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++
            line.startsWith("### ") -> { append("<h3>${inline(line.drop(4))}</h3>\n"); i++ }
            line.startsWith("## ") -> { append("<h2>${inline(line.drop(3))}</h2>\n"); i++ }
            line.startsWith("# ") -> { append("<h1>${inline(line.drop(2))}</h1>\n"); i++ }
            line.startsWith("- ") -> {
                append("<ul>\n")
                while (i < lines.size && lines[i].startsWith("- ")) { append("<li>${inline(lines[i].drop(2))}</li>\n"); i++ }
                append("</ul>\n")
            }
            line.startsWith("|") && i + 1 < lines.size && lines[i + 1].startsWith("|---") -> {
                append("<div class=\"table\"><table>\n<tr>")
                cells(line).forEach { append("<th>${inline(it)}</th>") }
                append("</tr>\n")
                i += 2
                while (i < lines.size && lines[i].startsWith("|")) {
                    append("<tr>"); cells(lines[i]).forEach { append("<td>${inline(it)}</td>") }; append("</tr>\n"); i++
                }
                append("</table></div>\n")
            }
            else -> {
                val paragraph = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") && !lines[i].startsWith("- ") && !lines[i].startsWith("|")) { paragraph += lines[i]; i++ }
                append("<p>${paragraph.joinToString("<br>") { inline(it) }}</p>\n")
            }
        }
    }
}

internal const val REPORT_CSS = """
:root { --bg: #fbf8fd; --fg: #1b1b1f; --muted: #5e5e66; --card: #efedf4; --line: #d6d4dc; --accent: #5b4ba7; --warn: #f9dedc; }
@media (prefers-color-scheme: dark) { :root { --bg: #131316; --fg: #e5e1e9; --muted: #a9a6b0; --card: #24232a; --line: #3a3940; --accent: #c7bfff; --warn: #5c1f1a; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--fg); font: 15px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
main { max-width: 960px; margin: 0 auto; padding: 24px 16px 48px; }
header { display: flex; gap: 16px; align-items: center; }
.icon { width: 56px; height: 56px; border-radius: 12px; }
h1 { margin: 0; font-size: 1.7em; } h2 { margin-top: 2em; border-bottom: 1px solid var(--line); padding-bottom: 4px; } h3 { margin-top: 1.5em; }
.muted { color: var(--muted); margin: 4px 0 0; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 10px; margin: 20px 0; }
.card { background: var(--card); border-radius: 12px; padding: 12px; } .card b { display: block; font-size: 1.5em; } .card span { color: var(--muted); }
.card.warn { background: var(--warn); }
code { background: var(--card); padding: 1px 5px; border-radius: 4px; font-size: .9em; word-break: break-all; }
ul { padding-left: 1.2em; } li { margin: 4px 0; }
.table { overflow-x: auto; } table { border-collapse: collapse; width: 100%; font-size: .92em; }
th, td { border-bottom: 1px solid var(--line); padding: 6px 8px; text-align: left; vertical-align: top; }
strong { color: var(--accent); }
td:first-child { min-width: 9em; } td:first-child code { word-break: normal; overflow-wrap: anywhere; }
"""
