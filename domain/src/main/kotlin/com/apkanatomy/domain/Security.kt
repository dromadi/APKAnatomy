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
    /** Split APKs whose signer differs from the base (or that are unsigned); Android refuses such a set. */
    val mismatchedSplits: List<String> = emptyList(),
)

data class SecurityFlags(
    val debuggable: Boolean, val allowBackup: Boolean, val cleartextTraffic: Boolean,
    val testOnly: Boolean, val targetSdk: Int,
    /** Resource reference of android:networkSecurityConfig, if declared. */
    val networkSecurityConfig: String? = null,
)

/** One scope of a Network Security Config: base-config, a domain-config, or debug-overrides. */
data class NscScope(
    /** Domains with "*." when includeSubdomains is set; empty for base-config and debug-overrides. */
    val domains: List<String> = emptyList(),
    /** Null when the scope does not set it (inherits). */
    val cleartextPermitted: Boolean? = null,
    /** Certificate sources: "system", "user", or a resource such as "@raw/company_ca"; null when inherited. */
    val trustAnchors: List<String>? = null,
    val pins: Int = 0,
    val pinExpiration: String? = null,
    /** <certificateTransparency enabled="..."> when declared (newer platform versions). */
    val certificateTransparency: Boolean? = null,
)

data class NetworkSecurityConfig(
    /** Path inside the APK; R8 may have shortened it (e.g. res/8G.xml). */
    val file: String,
    val base: NscScope? = null,
    val domains: List<NscScope> = emptyList(),
    val debugOverrides: NscScope? = null,
)

enum class NoteLevel { BAIK, INFO, PERHATIAN }
data class SecurityNote(val level: NoteLevel, val title: String, val explanation: String)

/** Educational reading of the signing and manifest flags; observations, not a vulnerability verdict. */
fun securityNotes(flags: SecurityFlags?, signing: SigningReport, now: Long, nsc: NetworkSecurityConfig? = null): List<SecurityNote> = buildList {
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
    if (signing.mismatchedSplits.isNotEmpty()) add(SecurityNote(NoteLevel.PERHATIAN, "Split dengan penandatangan berbeda",
        "Semua APK satu aplikasi wajib ditandatangani kunci yang sama. Berbeda dari base: ${signing.mismatchedSplits.joinToString()}. Android menolak memasang kombinasi ini; bundle mungkin dirakit ulang pihak lain."))
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
    // With a Network Security Config, android:usesCleartextTraffic is ignored and base-config decides (default: target SDK < 28).
    val cleartext = if (nsc != null) nsc.base?.cleartextPermitted ?: (flags.targetSdk < 28) else flags.cleartextTraffic
    val source = if (nsc != null) "Network Security Config (${nsc.file})" else "Manifest"
    add(if (cleartext) SecurityNote(NoteLevel.PERHATIAN, "Lalu lintas HTTP diizinkan",
            "$source mengizinkan koneksi tanpa enkripsi (cleartext) secara umum." +
                (if (nsc == null && flags.networkSecurityConfig != null) " Network Security Config (${flags.networkSecurityConfig}) dapat membatasinya per domain." else ""))
        else SecurityNote(NoteLevel.BAIK, "Cleartext tidak diizinkan", "$source membatasi aplikasi ke koneksi terenkripsi secara umum."))
    if (nsc != null) addAll(networkNotes(nsc, flags.targetSdk, now))
    else if (flags.targetSdk < 24) add(SecurityNote(NoteLevel.PERHATIAN, "CA pengguna dipercaya (target SDK ${flags.targetSdk})",
        "Aplikasi dengan target SDK di bawah 24 tanpa Network Security Config mempercayai sertifikat CA yang dipasang pengguna, sehingga lalu lintas HTTPS-nya dapat diintersep dengan proxy."))
    if (flags.testOnly) add(SecurityNote(NoteLevel.INFO, "android:testOnly = true", "Build khusus pengujian; hanya dapat dipasang lewat adb dengan opsi -t."))
}

private fun networkNotes(nsc: NetworkSecurityConfig, targetSdk: Int, now: Long): List<SecurityNote> = buildList {
    val baseAnchors = nsc.base?.trustAnchors ?: if (targetSdk < 24) listOf("system", "user") else listOf("system")
    if ("user" in baseAnchors) add(SecurityNote(NoteLevel.PERHATIAN, "CA pengguna dipercaya",
        "Konfigurasi dasar mempercayai sertifikat CA yang dipasang pengguna; lalu lintas HTTPS dapat diintersep dengan proxy yang sertifikatnya dipasang di perangkat."))
    nsc.domains.filter { "user" in it.trustAnchors.orEmpty() }.forEach {
        add(SecurityNote(NoteLevel.PERHATIAN, "CA pengguna dipercaya untuk ${it.domains.joinToString()}", "Domain ini menerima sertifikat dari CA yang dipasang pengguna."))
    }
    nsc.domains.filter { it.cleartextPermitted == true }.takeIf { it.isNotEmpty() }?.let { scopes ->
        add(SecurityNote(NoteLevel.INFO, "Cleartext untuk domain tertentu", "HTTP tanpa enkripsi diizinkan untuk: ${scopes.flatMap { it.domains }.joinToString()}."))
    }
    nsc.domains.filter { it.pins > 0 }.forEach { scope ->
        val expired = scope.pinExpiration?.let { runCatching { java.time.LocalDate.parse(it).toEpochDay() * 86_400_000L < now }.getOrNull() } == true
        add(if (expired) SecurityNote(NoteLevel.INFO, "Pin sertifikat kedaluwarsa", "Pin untuk ${scope.domains.joinToString()} berakhir ${scope.pinExpiration}; setelah tanggal itu Android tidak lagi menegakkan pinning.")
            else SecurityNote(NoteLevel.BAIK, "Certificate pinning", "${scope.pins} pin untuk ${scope.domains.joinToString()}" +
                (scope.pinExpiration?.let { ", berlaku sampai $it" } ?: "") + ". Koneksi hanya diterima bila rantai sertifikat memuat kunci yang dipin."))
    }
    nsc.domains.flatMap { it.trustAnchors.orEmpty() }.filter { it != "system" && it != "user" }.distinct().takeIf { it.isNotEmpty() }?.let {
        add(SecurityNote(NoteLevel.INFO, "CA khusus", "Sebagian domain mempercayai sertifikat yang dibundel aplikasi: ${it.joinToString()}."))
    }
    listOfNotNull(nsc.base?.takeIf { it.certificateTransparency == false }?.let { "konfigurasi dasar" }) +
        nsc.domains.filter { it.certificateTransparency == false }.map { it.domains.joinToString() }
    .takeIf { it.isNotEmpty() }?.let { scopes ->
        add(SecurityNote(NoteLevel.INFO, "Certificate Transparency dinonaktifkan", "Pemeriksaan bahwa sertifikat server tercatat di log publik CT dimatikan untuk: ${scopes.joinToString("; ")}."))
    }
    if (nsc.debugOverrides != null) add(SecurityNote(NoteLevel.INFO, "debug-overrides", "Aturan tambahan yang hanya berlaku bila aplikasi debuggable" +
        (nsc.debugOverrides.trustAnchors?.let { " (sumber CA: ${it.joinToString()})" } ?: "") + "."))
}

/** All audit notes for a report; use this rather than [securityNotes] so no input is forgotten. */
fun AnatomyReport.notes(now: Long): List<SecurityNote> = securityNotes(security, signing, now, networkSecurity) + capabilities.securityNotes() +
    traces.secrets.filter { it.level == NoteLevel.PERHATIAN }.map { it.type }.distinct().map { type ->
        SecurityNote(NoteLevel.PERHATIAN, "Kandidat rahasia: $type", "String yang mirip $type ditemukan; lihat tab Jejak. Bila asli, kredensial ini dapat dipakai siapa pun yang membedah APK.")
    }
