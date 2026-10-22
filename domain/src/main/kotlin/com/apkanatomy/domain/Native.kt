package com.apkanatomy.domain

data class NativeLibInfo(
    val archive: String, val path: String, val abi: String, val size: Long,
    /** e.g. "AArch64", "ARM", "x86-64"; null when the file is not a readable ELF. */
    val machine: String?, val bits: Int?,
    /** Smallest p_align of the LOAD segments; 16 KB devices need at least 16384. */
    val minLoadAlign: Long?,
    /** Stored uncompressed in the APK (loadable straight from it when extractNativeLibs is false). */
    val stored: Boolean, val zipDataOffset: Long?,
    val soname: String?, val needed: List<String>,
    /** Statically registered JNI functions, demangled to "com.example.Foo.bar". */
    val jniMethods: List<String>, val hasJniOnLoad: Boolean,
    val hasSymbolTable: Boolean, val hasDebugInfo: Boolean,
    val note: String? = null,
    /** Imported functions worth explaining (see [NATIVE_IMPORTS]), sorted. */
    val notableImports: List<String> = emptyList(),
) : java.io.Serializable {
    val is64Bit: Boolean get() = bits == 64
    val elfAligned16k: Boolean? get() = minLoadAlign?.let { it >= PAGE_16K }
    val zipAligned16k: Boolean? get() = if (stored) zipDataOffset?.let { it % PAGE_16K == 0L } else null
}

data class NativeReport(
    val libraries: List<NativeLibInfo> = emptyList(),
    /** android:extractNativeLibs as the package manager reports it; null when unknown. */
    val extractNativeLibs: Boolean? = null,
    val warnings: List<String> = emptyList(),
) : java.io.Serializable

const val PAGE_16K = 16_384L

/** Libraries that could fail to load on a device with 16 KB memory pages, each with the reason. */
fun NativeReport.pageSizeProblems(): List<Pair<NativeLibInfo, String>> = libraries.filter { it.is64Bit }.mapNotNull { lib ->
    val reasons = buildList {
        if (lib.elfAligned16k == false) add("segmen LOAD rata ${lib.minLoadAlign!! / 1024} KB")
        // Only libraries loaded straight from the APK need a 16 KB aligned ZIP offset.
        if (extractNativeLibs == false && lib.zipAligned16k == false) add("offset ZIP ${lib.zipDataOffset} bukan kelipatan 16 KB")
    }
    if (reasons.isEmpty()) null else lib to reasons.joinToString(" dan ")
}

/** Readiness for 16 KB page-size devices, or null when the app ships no 64-bit native code. */
fun NativeReport.pageSizeNote(): SecurityNote? {
    val libs64 = libraries.filter { it.is64Bit }
    if (libs64.isEmpty()) return null
    val problems = pageSizeProblems()
    return if (problems.isEmpty()) SecurityNote(NoteLevel.BAIK, "Siap 16 KB page size",
        "Semua ${libs64.size} library 64-bit memiliki segmen rata 16 KB" + (if (extractNativeLibs == false) " dan offset ZIP rata 16 KB." else "."))
    else SecurityNote(NoteLevel.PERHATIAN, "Belum siap 16 KB page size",
        "Pada perangkat Android 15+ dengan halaman memori 16 KB, library ini dapat gagal dimuat: " +
            problems.joinToString("; ") { (lib, why) -> "${lib.abi}/${lib.path.substringAfterLast('/')} ($why)" } +
            ". Google Play mewajibkan dukungan 16 KB untuk aplikasi baru dan pembaruan yang menargetkan Android 15 ke atas.")
}

/** Turns a JNI symbol (Java_com_ex_Foo_bar or Java_com_ex_Foo_bar__I) into "com.ex.Foo.bar"; null if malformed. */
fun demangleJni(symbol: String): String? {
    if (!symbol.startsWith("Java_")) return null
    val body = symbol.removePrefix("Java_").substringBefore("__")
    val out = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '_') { out.append(c); i++; continue }
        when (body.getOrNull(i + 1)) {
            '1' -> { out.append('_'); i += 2 }
            '2' -> { out.append(';'); i += 2 }
            '3' -> { out.append('['); i += 2 }
            '0' -> {
                val hex = body.substring(i + 2, minOf(i + 6, body.length))
                if (hex.length < 4) return null
                out.append(hex.toIntOrNull(16)?.toChar() ?: return null); i += 6
            }
            else -> { out.append('.'); i++ }
        }
    }
    return out.toString().takeIf { '.' in it }
}

/** What an imported native function suggests: (group, explanation). Only names worth a learner's attention are listed. */
val NATIVE_IMPORTS: Map<String, Pair<String, String>> = buildMap {
    fun group(title: String, lesson: String, vararg names: String) = names.forEach { put(it, title to lesson) }
    group("Anti-debug / pelacakan proses", "ptrace dipakai debugger; aplikasi memanggilnya sendiri untuk mencegah debugger lain menempel atau untuk memeriksa apakah sedang dilacak.", "ptrace")
    group("Menjalankan program lain", "Fungsi ini menjalankan perintah atau program lain (shell, su, getprop) dari kode native.",
        "execve", "execv", "execvp", "execl", "execlp", "system", "popen", "fork", "vfork", "posix_spawn")
    group("Memuat library secara dinamis", "dlopen/dlsym memuat library dan mencari fungsi saat berjalan; library yang dimuat tidak terlihat di daftar NEEDED.",
        "dlopen", "android_dlopen_ext", "dlsym")
    group("Mengubah izin memori", "mprotect/mmap dengan izin eksekusi bisa dipakai JIT, tetapi juga oleh packer untuk membuka kode terenkripsi di memori.", "mprotect")
    group("Membaca properti sistem", "Properti seperti ro.build.tags atau ro.product.model dipakai untuk mengenali perangkat, emulator, atau build yang di-root.",
        "__system_property_get", "__system_property_find", "__system_property_read_callback")
    group("Jaringan dari kode native", "Socket dibuka langsung dari native, di luar API jaringan Java dan Network Security Config.", "socket", "connect", "getaddrinfo", "gethostbyname")
    group("Membaca antarmuka jaringan", "getifaddrs memberi alamat IP dan nama antarmuka (mis. untuk mendeteksi VPN).", "getifaddrs")
    group("TLS sendiri (OpenSSL/BoringSSL)", "Library membawa TLS sendiri; pemeriksaan sertifikatnya tidak mengikuti Network Security Config aplikasi.",
        "SSL_CTX_new", "SSL_connect", "SSL_new", "SSL_CTX_set_verify")
    group("Memantau file", "inotify memberi tahu saat file berubah atau dibuka; dipakai sinkronisasi, tetapi juga deteksi tamper/debugger.", "inotify_init", "inotify_init1", "inotify_add_watch")
    group("Syscall langsung", "syscall memanggil kernel tanpa lewat libc, sering untuk menghindari hook pada fungsi libc.", "syscall")
}

fun notableImports(imports: Collection<String>): List<String> = imports.filter { it in NATIVE_IMPORTS }.distinct().sorted()

/** A group of notable imports across all native libraries, with the libraries that use it. */
data class NativeImportFinding(val title: String, val lesson: String, val symbols: List<String>, val libraries: List<String>)

fun NativeReport.importFindings(): List<NativeImportFinding> = libraries
    .flatMap { lib -> lib.notableImports.map { symbol -> Triple(NATIVE_IMPORTS.getValue(symbol), symbol, lib.path.substringAfterLast('/')) } }
    .groupBy { it.first }
    .map { (group, hits) -> NativeImportFinding(group.first, group.second, hits.map { it.second }.distinct().sorted(), hits.map { it.third }.distinct().sorted()) }
    .sortedBy { it.title }
