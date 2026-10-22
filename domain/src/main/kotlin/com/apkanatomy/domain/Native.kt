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
