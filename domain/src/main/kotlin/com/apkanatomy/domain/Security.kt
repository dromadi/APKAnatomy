package com.apkanatomy.domain

enum class SignatureScheme(val label: String) {
    V1("v1 (JAR signing)"), V2("v2"), V3("v3"), V31("v3.1"), V4("v4"), SOURCE_STAMP("Source stamp"),
}

data class SignerCertificate(
    val subject: String, val issuer: String, val serialHex: String,
    val notBefore: Long, val notAfter: Long, val signatureAlgorithm: String,
    val keyAlgorithm: String, val keySize: Int?,
    val sha256: String, val sha1: String, val md5: String,
    /** Where the certificate appears, e.g. "v1", "v3 (API 24–32)". */
    val foundIn: List<String>,
) {
    val isAndroidDebug: Boolean get() = subject.contains("CN=Android Debug")
}

data class SigningReport(
    val schemes: Set<SignatureScheme> = emptySet(),
    val certificates: List<SignerCertificate> = emptyList(),
    /** A v3 signer carries a proof-of-rotation lineage (the signing key was replaced). */
    val keyRotation: Boolean = false,
    /** Non-signature entries of the APK Signing Block, already described. */
    val otherBlocks: List<String> = emptyList(),
    /** Whether a v4 .idsig file could be looked for; false for a single imported APK. */
    val v4Checked: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class SecurityFlags(
    val debuggable: Boolean, val allowBackup: Boolean, val cleartextTraffic: Boolean,
    val testOnly: Boolean, val targetSdk: Int,
    /** Resource reference of android:networkSecurityConfig, if declared. */
    val networkSecurityConfig: String? = null,
)

enum class NoteLevel { BAIK, INFO, PERHATIAN }
data class SecurityNote(val level: NoteLevel, val title: String, val explanation: String)

/** Educational reading of the signing and manifest flags; observations, not a vulnerability verdict. */
fun securityNotes(flags: SecurityFlags?, signing: SigningReport, now: Long): List<SecurityNote> = buildList {
    val schemes = signing.schemes
    when {
        schemes.isEmpty() && signing.warnings.isEmpty() -> add(SecurityNote(NoteLevel.PERHATIAN, "Tidak bertanda tangan",
            "Tidak ditemukan tanda tangan v1, v2, atau v3. Android menolak memasang APK tanpa tanda tangan."))
        schemes.isEmpty() -> add(SecurityNote(NoteLevel.INFO, "Tanda tangan tidak terbaca",
            "Blok tanda tangan tidak dapat dibaca sepenuhnya; lihat peringatan di bawah."))
        schemes.none { it == SignatureScheme.V2 || it == SignatureScheme.V3 || it == SignatureScheme.V31 } &&
            flags != null && flags.targetSdk >= 30 -> add(SecurityNote(NoteLevel.PERHATIAN, "Hanya v1 untuk target SDK ${flags.targetSdk}",
            "Sejak Android 11, aplikasi dengan target SDK 30 ke atas wajib memakai skema v2 atau lebih baru. APK ini kemungkinan ditolak saat dipasang."))
        SignatureScheme.V2 !in schemes && SignatureScheme.V3 !in schemes && SignatureScheme.V31 !in schemes ->
            add(SecurityNote(NoteLevel.INFO, "Hanya skema v1", "Skema v1 hanya melindungi isi file di dalam ZIP, bukan seluruh arsip. Skema v2+ memverifikasi APK sebagai satu kesatuan dan lebih cepat diperiksa."))
        else -> add(SecurityNote(NoteLevel.BAIK, "Tanda tangan modern", "Ada skema ${schemes.filter { it != SignatureScheme.V1 && it != SignatureScheme.SOURCE_STAMP }.joinToString { it.label }} yang melindungi seluruh arsip APK."))
    }
    if (signing.keyRotation) add(SecurityNote(NoteLevel.INFO, "Rotasi kunci",
        "Skema v3 membawa riwayat kunci (proof-of-rotation): kunci penandatangan pernah diganti, dan perangkat baru mengenali kunci lama sebagai pendahulunya."))
    signing.certificates.filter { it.isAndroidDebug }.forEach { _ ->
        add(SecurityNote(NoteLevel.PERHATIAN, "Sertifikat debug Android", "Ditandatangani kunci debug bawaan Android SDK. Biasanya hanya untuk pengembangan; build rilis memakai kunci milik developer."))
    }
    signing.certificates.filter { it.notAfter < now }.forEach {
        add(SecurityNote(NoteLevel.INFO, "Sertifikat kedaluwarsa", "Masa berlaku ${it.subject} sudah lewat. Android tidak memeriksa masa berlaku saat memasang, tetapi ini petunjuk umur atau pengelolaan kunci."))
    }
    signing.certificates.filter { it.signatureAlgorithm.contains("MD5", true) || it.signatureAlgorithm.startsWith("SHA1", true) }.forEach {
        add(SecurityNote(NoteLevel.INFO, "Algoritma sertifikat lama", "${it.signatureAlgorithm} dipakai pada sertifikat ${it.subject}. Algoritma hash ini sudah dianggap lemah untuk tanda tangan baru."))
    }
    if (flags == null) return@buildList
    add(if (flags.debuggable) SecurityNote(NoteLevel.PERHATIAN, "android:debuggable = true",
            "Debugger dapat menempel ke proses aplikasi dan data privatnya dapat dibaca lewat run-as. Wajar untuk build debug, tidak untuk rilis.")
        else SecurityNote(NoteLevel.BAIK, "Tidak debuggable", "Build tidak mengizinkan debugger menempel ke prosesnya."))
    add(if (flags.allowBackup) SecurityNote(NoteLevel.INFO, "android:allowBackup = true",
            "Data aplikasi boleh ikut dicadangkan (cloud backup dan transfer perangkat). Untuk target SDK 31 ke atas, adb backup tidak lagi menyertakan data aplikasi yang tidak debuggable.")
        else SecurityNote(NoteLevel.BAIK, "Backup dinonaktifkan", "Data aplikasi tidak ikut dicadangkan oleh sistem."))
    add(if (flags.cleartextTraffic) SecurityNote(NoteLevel.PERHATIAN, "Lalu lintas HTTP diizinkan",
            "Manifest mengizinkan koneksi tanpa enkripsi (cleartext). " +
                (if (flags.networkSecurityConfig != null) "Network Security Config (${flags.networkSecurityConfig}) tetap dapat membatasinya per domain." else "Tidak ada Network Security Config yang mempersempitnya."))
        else SecurityNote(NoteLevel.BAIK, "Cleartext tidak diizinkan", "Secara default aplikasi hanya memakai koneksi terenkripsi." +
            (flags.networkSecurityConfig?.let { " Network Security Config ($it) dapat membuat pengecualian per domain." } ?: "")))
    if (flags.testOnly) add(SecurityNote(NoteLevel.INFO, "android:testOnly = true", "Build khusus pengujian; hanya dapat dipasang lewat adb dengan opsi -t."))
}
