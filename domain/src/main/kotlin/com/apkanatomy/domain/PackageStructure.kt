package com.apkanatomy.domain

data class ArchiveEntry(val archive: String, val path: String, val size: Long, val compressedSize: Long) : java.io.Serializable {
    val id: String get() = "$archive:$path"
}
data class NativeLibrary(val archive: String, val path: String, val abi: String, val size: Long) : java.io.Serializable
data class DexMetrics(val archive: String, val path: String, val version: String?,
    val methodReferences: Long?, val classDefinitions: Long?, val note: String?) : java.io.Serializable
data class Technology(val name: String, val explanation: String, val evidence: List<String>) : java.io.Serializable
data class PackageStructure(
    val archives: List<String> = emptyList(), val entries: List<ArchiveEntry> = emptyList(),
    val libraries: List<NativeLibrary> = emptyList(), val dex: List<DexMetrics> = emptyList(),
    val technologies: List<Technology> = emptyList(), val warnings: List<String> = emptyList(),
    /** "group:artifact" to version, from META-INF/<group>_<artifact>.version files left by Gradle. */
    val libraryVersions: Map<String, String> = emptyMap(),
) : java.io.Serializable
fun explainEntry(path: String): String = when {
    path == "AndroidManifest.xml" -> "Deklarasi identitas, komponen, izin, dan konfigurasi aplikasi. File ini biasanya berformat XML biner."
    path == "resources.arsc" -> "Tabel resource terkompilasi: menghubungkan ID resource dengan nilai, bahasa, dan konfigurasi perangkat."
    Regex("classes([0-9]+)?\\.dex").matches(path) -> "Bytecode Android: memuat class dan referensi method. Dapat ditelusuri lebih lanjut dengan pembaca DEX; bukan kode sumber asli."
    path.startsWith("lib/") && path.endsWith(".so") -> "Library native: kode mesin untuk ABI tertentu, sering berasal dari C/C++ atau runtime framework. Nama file memberi petunjuk, bukan kepastian fungsi."
    path.startsWith("res/") -> "Resource aplikasi seperti gambar, XML terkompilasi, warna, dan font. Compose tidak harus menyimpan layar sebagai layout XML."
    path.startsWith("assets/") -> "File yang dibundel aplikasi: bisa berupa konfigurasi, media, model, web bundle, atau data framework."
    path.startsWith("META-INF/") -> "Metadata paket: dapat mencakup signing v1 dan metadata library. Skema v2/v3 tidak dapat ditentukan dari folder ini saja."
    else -> "File di dalam paket APK. Fungsi tepatnya perlu ditelusuri dari isi file dan referensi kode."
}
fun componentLesson(kind: ComponentKind): String = when (kind) {
    ComponentKind.ACTIVITY -> "Activity menjadi titik masuk layar atau interaksi pengguna. Satu Activity dapat memuat banyak layar, misalnya dengan Compose."
    ComponentKind.SERVICE -> "Service menyediakan komponen kerja tanpa layar. Deklarasinya belum menunjukkan kapan ia berjalan atau tugas tepatnya."
    ComponentKind.RECEIVER -> "Receiver menerima broadcast yang sesuai. Intent-filter membantu menelusuri pemicunya; receiver dinamis tidak selalu tercatat di manifest."
    ComponentKind.PROVIDER -> "Provider menyajikan akses data melalui content URI. Authority memberi alamatnya; izin baca/tulis dapat membatasi akses."
}

/** What a file inside the APK looks like, read on demand from the archive. */
sealed interface FilePreview {
    /** [kind] says how the text was obtained, e.g. "XML biner (didekode)". */
    data class Text(val text: String, val kind: String, val truncated: Boolean) : FilePreview
    class Image(val bytes: ByteArray, val format: String) : FilePreview
    /** Hex and ASCII columns of the first bytes, for files with no readable form. */
    data class Hex(val dump: String, val note: String) : FilePreview
    data class Unavailable(val reason: String) : FilePreview
}
