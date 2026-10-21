package com.apkanatomy.data

import com.apkanatomy.domain.*
import java.io.File
import java.util.zip.ZipFile

/** Inspects ZIP metadata and fixed-size DEX headers; never extracts package contents. */
internal class StructureScanner(private val entryLimit: Int = 50_000) {
    init { require(entryLimit > 0) }
    fun scan(files: List<File>): PackageStructure {
        val entries = mutableListOf<ArchiveEntry>()
        val libraries = mutableListOf<NativeLibrary>()
        val dex = mutableListOf<DexMetrics>()
        val warnings = mutableListOf<String>()
        val archives = mutableListOf<String>()
        val versions = sortedMapOf<String, String>()
        var visited = 0
        for ((index, file) in files.withIndex()) {
            if (visited >= entryLimit) { warnings.add("Batas $entryLimit entri tercapai; hasil struktur hanya sebagian."); break }
            val archive = if (index == 0) "base.apk" else file.name
            try {
                ZipFile(file).use { zip ->
                    archives.add(archive)
                    val iterator = zip.entries()
                    val seen = mutableSetOf<String>()
                    while (iterator.hasMoreElements()) {
                        if (visited >= entryLimit) { warnings.add("Batas $entryLimit entri tercapai; hasil struktur hanya sebagian."); break }
                        val entry = iterator.nextElement()
                        visited++
                        if (entry.isDirectory) continue
                        if (!seen.add(entry.name)) { warnings.add("Entri duplikat pada $archive: ${entry.name}"); continue }
                        entries.add(ArchiveEntry(archive, entry.name, entry.size, entry.compressedSize))
                        val parts = entry.name.split('/')
                        if (parts.size == 3 && parts[0] == "lib" && parts[2].endsWith(".so")) {
                            libraries.add(NativeLibrary(archive, entry.name, parts[1], entry.size))
                        }
                        readLibraryVersion(zip, entry, versions)
                        if (Regex("classes([0-9]+)?\\.dex").matches(entry.name)) {
                            val metrics = try {
                                zip.getInputStream(entry).use { input ->
                                    val header = ByteArray(112)
                                    var read = 0
                                    while (read < header.size) {
                                        val count = input.read(header, read, header.size - read)
                                        if (count <= 0) break
                                        read += count
                                    }
                                    parseDexHeader(header.copyOf(read), entry.size, archive, entry.name)
                                }
                            } catch (_: Exception) {
                                DexMetrics(archive, entry.name, null, null, null, "Header DEX tidak dapat dibaca.")
                            }
                            dex.add(metrics)
                        }
                    }
                }
            } catch (_: Exception) { warnings.add("Arsip $archive tidak dapat dibaca; aplikasi mungkin berubah atau file tidak valid.") }
        }
        return PackageStructure(archives, entries.sortedWith(compareBy({ it.archive }, { it.path })),
            libraries.sortedWith(compareBy({ it.abi }, { it.path })), dex,
            detectTechnologies(entries), warnings.distinct(), versions)
    }

    /** Reads META-INF/<group>_<artifact>.version (a few bytes holding a version string). */
    private fun readLibraryVersion(zip: ZipFile, entry: java.util.zip.ZipEntry, into: MutableMap<String, String>) {
        val name = entry.name
        if (!name.startsWith("META-INF/") || !name.endsWith(".version") || name.count { it == '/' } != 1) return
        if (entry.size !in 1..128 || into.size >= 2_000) return
        val coordinate = name.removePrefix("META-INF/").removeSuffix(".version")
        val group = coordinate.substringBefore('_', "")
        val artifact = coordinate.substringAfter('_', "")
        if (group.isEmpty() || artifact.isEmpty()) return
        // The declared size can lie; copyBounded caps what is actually inflated.
        val version = try {
            java.io.ByteArrayOutputStream().also { out -> zip.getInputStream(entry).use { copyBounded(it, out, 128) } }.toString(Charsets.UTF_8.name()).trim()
        } catch (_: Exception) { return }
        if (VERSION.matches(version)) into["$group:$artifact"] = version
    }

    private companion object { val VERSION = Regex("[0-9A-Za-z.+_-]{1,40}") }
}

internal fun parseDexHeader(bytes: ByteArray, size: Long, archive: String, path: String): DexMetrics {
    fun invalid(message: String) = DexMetrics(archive, path, null, null, null, message)
    if (bytes.size < 112) return invalid("Header DEX terpotong.")
    if (!bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10)) || bytes[7] != 0.toByte()) return invalid("Magic DEX tidak valid.")
    val version = bytes.copyOfRange(4, 7).toString(Charsets.US_ASCII)
    if (version !in setOf("035", "037", "038", "039", "040")) return invalid("Versi DEX $version belum didukung.")
    fun u32(offset: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (i * 8)) }
    if (u32(36) != 112L || u32(40) != 0x12345678L) return invalid("Layout/endian header DEX belum didukung.")
    if (u32(32) != size || size < 112) return invalid("Ukuran file DEX tidak cocok dengan header.")
    fun validSection(count: Long, offset: Long, width: Long): Boolean =
        if (count == 0L) offset == 0L else offset >= 112 && offset % 4 == 0L && offset <= size && count <= (size - offset) / width
    val methods = u32(88)
    val classes = u32(96)
    if (!validSection(methods, u32(92), 8) || !validSection(classes, u32(100), 32)) return invalid("Batas tabel method/class DEX tidak valid.")
    return DexMetrics(archive, path, version, methods, classes, null)
}

internal fun detectTechnologies(entries: List<ArchiveEntry>): List<Technology> {
    val results = mutableListOf<Technology>()
    fun detect(name: String, explanation: String, matches: (String) -> Boolean) {
        val evidence = entries.filter { matches(it.path) }.map { "${it.archive}/${it.path}" }.take(12)
        if (evidence.isNotEmpty()) results.add(Technology(name, explanation, evidence))
    }
    detect("Flutter", "Runtime Flutter ditemukan. Ini petunjuk bahwa sebagian aplikasi menggunakan Flutter; belum membuktikan seluruh layar dibuat dengan Flutter.") { it.startsWith("lib/") && it.endsWith("/libflutter.so") }
    detect("React Native", "Library/bundle khas React Native ditemukan. Telusuri bundle dan referensi kode untuk memahami fitur yang menggunakannya.") { it.startsWith("lib/") && (it.endsWith("/libreactnative.so") || it.endsWith("/libreactnativejni.so")) || it == "assets/index.android.bundle" }
    detect("Hermes", "Mesin JavaScript Hermes ditemukan. Sering digunakan bersama React Native, tetapi keberadaannya sendiri tidak memastikan framework aplikasi.") { it.startsWith("lib/") && it.endsWith("/libhermes.so") }
    detect("Unity", "Runtime Unity ditemukan; aplikasi mungkin memuat game atau modul berbasis Unity.") { it.startsWith("lib/") && it.endsWith("/libunity.so") }
    detect("Godot", "Library dengan nama runtime Godot ditemukan; konfirmasi melalui isi assets dan referensi kode.") { it.startsWith("lib/") && (it.endsWith("/libgodot_android.so") || it.endsWith("/libgodot.so")) }
    detect("Mono / Xamarin", "Runtime Mono Android ditemukan. Ini petunjuk ekosistem .NET/Mono, bukan identifikasi pasti versi atau produk.") { it.startsWith("lib/") && it.endsWith("/libmonodroid.so") }
    detect("Capacitor", "Konfigurasi Capacitor ditemukan; telusuri web assets untuk mempelajari bagian antarmuka berbasis web.") { it == "assets/capacitor.config.json" || it == "assets/public/capacitor.config.json" }
    detect("Metadata Kotlin", "Metadata kompilasi Kotlin ditemukan. Bisa berasal dari library pihak ketiga dan tidak membuktikan seluruh aplikasi ditulis dalam Kotlin.") { it.startsWith("META-INF/") && it.endsWith(".kotlin_module") }
    return results
}
