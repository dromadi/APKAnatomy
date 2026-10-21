package com.apkanatomy.domain

enum class SizeCategory(val label: String, val lesson: String) {
    DEX("Kode DEX", "Bytecode aplikasi dan semua library-nya."),
    NATIVE("Library native", "Kode mesin per ABI di lib/; sering terbesar pada game dan aplikasi cross-platform."),
    RES("Resource (res/)", "Gambar, layout, font, dan XML terkompilasi."),
    ARSC("resources.arsc", "Tabel resource: string, warna, dimensi untuk setiap bahasa dan konfigurasi."),
    ASSETS("Assets", "File mentah yang dibundel: web bundle, model, media, konfigurasi."),
    META("Metadata", "META-INF dan metadata Kotlin: tanda tangan v1, versi library, dan sejenisnya."),
    OTHER("Lainnya", "File lain di akar arsip atau folder khusus."),
}

data class SizeSlice(val category: SizeCategory, val files: Int, val size: Long, val compressedSize: Long)

fun categorize(path: String): SizeCategory = when {
    Regex("classes([0-9]+)?\\.dex").matches(path) -> SizeCategory.DEX
    path.startsWith("lib/") -> SizeCategory.NATIVE
    path == "resources.arsc" -> SizeCategory.ARSC
    path.startsWith("res/") || path == "AndroidManifest.xml" -> SizeCategory.RES
    path.startsWith("assets/") -> SizeCategory.ASSETS
    path.startsWith("META-INF/") || path.startsWith("kotlin/") -> SizeCategory.META
    else -> SizeCategory.OTHER
}

/** Uncompressed size per category across all archives, largest first; unknown sizes are skipped. */
fun sizeBreakdown(entries: List<ArchiveEntry>): List<SizeSlice> =
    entries.filter { it.size >= 0 }.groupBy { categorize(it.path) }
        .map { (category, files) -> SizeSlice(category, files.size, files.sumOf { it.size }, files.sumOf { it.compressedSize.coerceAtLeast(0) }) }
        .filter { it.size > 0 }.sortedByDescending { it.size }

data class FileGroup(val title: String, val explanation: String, val files: List<ArchiveEntry>)

private class FileRule(val title: String, val explanation: String, val matches: (String) -> Boolean)
private fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
private val fileRules = listOf(
    FileRule("Font", "Font yang dibundel aplikasi untuk tampilan teksnya sendiri.") { ext(it) in setOf("ttf", "otf", "ttc") },
    FileRule("Model machine learning", "Model ML yang dijalankan di perangkat (mis. TensorFlow Lite atau ONNX).") { ext(it) in setOf("tflite", "lite", "onnx", "ptl", "mlmodel") },
    FileRule("Web bundle", "HTML/JavaScript di assets: sebagian antarmuka atau logika mungkin berjalan di WebView atau mesin JS.") { it.startsWith("assets/") && ext(it) in setOf("html", "htm", "js", "bundle", "hbc") },
    FileRule("Database bawaan", "Database yang ikut dibundel sebagai data awal.") { ext(it) in setOf("db", "sqlite", "sqlite3", "realm") },
    FileRule("Sertifikat & keystore", "Sertifikat atau keystore di dalam APK; sering dipakai untuk certificate pinning atau koneksi klien. Bukan tanda tangan APK.") {
        !it.startsWith("META-INF/") && ext(it) in setOf("pem", "crt", "cer", "der", "bks", "p12", "pfx", "jks", "keystore")
    },
    FileRule("Media", "Audio dan video yang dibundel.") { ext(it) in setOf("mp3", "ogg", "wav", "m4a", "aac", "mp4", "webm", "mkv") },
    FileRule("Konfigurasi JSON", "Konfigurasi atau data JSON di assets.") { it.startsWith("assets/") && ext(it) == "json" },
)

/** Files worth a look for someone studying how the app is built, grouped and largest first. */
fun notableFiles(entries: List<ArchiveEntry>): List<FileGroup> = fileRules.mapNotNull { rule ->
    entries.filter { rule.matches(it.path) }.sortedByDescending { it.size }.takeIf { it.isNotEmpty() }
        ?.let { FileGroup(rule.title, rule.explanation, it) }
}
