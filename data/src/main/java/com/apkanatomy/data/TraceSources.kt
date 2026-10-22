package com.apkanatomy.data

import com.apkanatomy.axml.ResourceTable
import com.apkanatomy.axml.XmlElement
import com.apkanatomy.domain.TraceCollector
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

private val MEDIA = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "mp3", "mp4", "ogg", "wav", "m4a", "aac", "webm", "mkv",
    "ttf", "otf", "ttc", "woff", "woff2", "so", "dex", "tflite", "onnx", "zip", "jar", "apk")

/** Feeds non-DEX sources to [traces]: resource strings, manifest meta-data and files under assets/ and res/raw/. */
internal object TraceSources {
    const val MAX_FILE_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    fun scanResources(table: ResourceTable?, traces: TraceCollector) {
        table?.stringValues()?.forEach { (name, value) -> traces.scan(value, "res: $name") }
    }

    fun scanManifest(application: XmlElement?, traces: TraceCollector) {
        application?.childElements("meta-data")?.forEach { meta ->
            val value = meta.attribute("value") ?: return@forEach
            traces.scan(value, "manifest meta-data ${meta.attribute("name") ?: "?"}")
        }
    }

    /** Reads raw files as Latin-1 so binary bundles (e.g. Hermes) still yield their ASCII strings. */
    fun scanFiles(archives: Map<String, File>, traces: TraceCollector, checkCancelled: () -> Unit = {}) {
        var total = 0L
        for ((label, file) in archives) {
            try {
                ZipFile(file).use { zip ->
                    for (entry in zip.entries()) {
                        checkCancelled()
                        val name = entry.name
                        if (entry.isDirectory || !(name.startsWith("assets/") || name.startsWith("res/raw/"))) continue
                        if (name.substringAfterLast('.', "").lowercase() in MEDIA) continue
                        if (entry.size !in 1..MAX_FILE_BYTES) continue
                        if (total + entry.size > MAX_TOTAL_BYTES) { traces.note("Pemindaian aset berhenti di batas ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB."); return }
                        val bytes = try {
                            ByteArrayOutputStream().also { out -> zip.getInputStream(entry).use { copyBounded(it, out, MAX_FILE_BYTES) } }.toByteArray()
                        } catch (_: IllegalArgumentException) { continue }
                        total += bytes.size
                        traces.scan(String(bytes, Charsets.ISO_8859_1), "$label/$name")
                    }
                }
            } catch (_: Exception) { /* Unreadable archives are already reported by StructureScanner. */ }
        }
    }
}
