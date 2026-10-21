package com.apkanatomy.data

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Unpacks split-APK bundles (.apks from bundletool/SAI, .xapk from APKPure, .apkm from APKMirror).
 * Entry names come from an untrusted file: only a sanitised base name is used for output files,
 * so no entry can write outside [outDir].
 */
internal class BundleExtractor(
    private val maxApks: Int = 64,
    private val maxApkBytes: Long = 512L * 1024 * 1024,
    private val maxTotalBytes: Long = 1024L * 1024 * 1024,
) {
    class Extracted(val format: String, val base: File, val splits: List<File>, val skipped: List<String>)
    class BundleException(message: String) : Exception(message)

    fun extract(bundle: File, outDir: File): Extracted {
        if (!outDir.isDirectory && !outDir.mkdirs()) throw IOException("Folder sementara tidak dapat dibuat.")
        val skipped = mutableListOf<String>()
        val extracted = mutableListOf<File>()
        val format: String
        ZipFile(bundle).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val names = entries.map { it.name }.toSet()
            format = when {
                "toc.pb" in names -> "APKS (bundletool)"
                "manifest.json" in names -> "XAPK"
                "info.json" in names -> "APKM"
                else -> "Bundle APK"
            }
            entries.filter { it.name.endsWith(".obb", ignoreCase = true) }.forEach { skipped += "${it.name}: file OBB (data tambahan game) tidak dianalisis." }
            val apks = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }.filter { entry ->
                // bundletool's standalone variants repeat the whole app for old devices.
                (!entry.name.startsWith("standalones/")).also { if (!it) skipped += "${entry.name}: varian standalone untuk perangkat lama dilewati." }
            }
            if (apks.isEmpty()) throw BundleException("Arsip tidak berisi APK.")
            if (apks.size > maxApks) throw BundleException("Bundle berisi ${apks.size} APK; batasnya $maxApks.")
            var total = 0L
            val used = mutableSetOf<String>()
            for (entry in apks) {
                val base = entry.name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifEmpty { "split.apk" }
                var name = base
                var n = 1
                while (!used.add(name.lowercase())) name = base.removeSuffix(".apk") + "-${n++}.apk"
                val target = File(outDir, name)
                try {
                    target.outputStream().use { out -> zip.getInputStream(entry).use { total += copyBounded(it, out, minOf(maxApkBytes, maxTotalBytes - total)) } }
                } catch (_: IllegalArgumentException) {
                    throw BundleException("Isi bundle melebihi batas ukuran (${maxApkBytes / (1024 * 1024)} MiB per APK, ${maxTotalBytes / (1024 * 1024)} MiB total).")
                }
                extracted += target
            }
        }
        // The base is the APK whose manifest has no split="..." attribute, whatever it is called.
        val manifests = extracted.associateWith { ManifestReader.read(it).root }
        manifests.filterValues { it == null }.keys.forEach { skipped += "${it.name}: manifest tidak terbaca, dilewati."; it.delete() }
        val readable = manifests.filterValues { it != null }.mapValues { it.value!! }
        val bases = readable.filterValues { it.attribute("split", namespaceUri = null) == null }.keys
        val base = when (bases.size) {
            1 -> bases.single()
            0 -> throw BundleException("Tidak ada base APK di bundle; hanya split.")
            else -> throw BundleException("Bundle berisi ${bases.size} APK tanpa atribut split (${bases.joinToString { it.name }}); tidak jelas mana base-nya.")
        }
        val packageName = readable.getValue(base).attribute("package", namespaceUri = null)
        val splits = (readable.keys - base).filter { split ->
            (readable.getValue(split).attribute("package", namespaceUri = null) == packageName).also {
                if (!it) { skipped += "${split.name}: package berbeda dari base, dilewati."; split.delete() }
            }
        }.sortedBy { it.name }
        return Extracted(format, base, splits, skipped)
    }
}
