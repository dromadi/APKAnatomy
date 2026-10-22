package com.apkanatomy.domain

/** The copy of the same package installed on the device, as the package manager reports it. */
data class InstalledCopy(
    val versionName: String, val versionCode: Long,
    /** SHA-256 of the current signing certificates. */
    val signers: List<String>,
    /** SHA-256 of earlier certificates in the key rotation history (oldest first), without the current ones. */
    val pastSigners: List<String> = emptyList(),
    val installer: String? = null,
) : java.io.Serializable

private val INSTALLERS = mapOf(
    "com.android.vending" to "Google Play",
    "org.fdroid.fdroid" to "F-Droid",
    "org.fdroid.basic" to "F-Droid",
    "com.aurora.store" to "Aurora Store",
    "com.sec.android.app.samsungapps" to "Galaxy Store",
    "com.huawei.appmarket" to "Huawei AppGallery",
    "com.xiaomi.market" to "Xiaomi GetApps",
    "com.xiaomi.mipicks" to "Xiaomi GetApps",
    "com.amazon.venezia" to "Amazon Appstore",
    "com.google.android.packageinstaller" to "penginstal paket (file APK)",
    "com.android.packageinstaller" to "penginstal paket (file APK)",
)

/** "com.android.vending" → "Google Play (com.android.vending)"; null means adb, the system image, or unknown. */
fun describeInstaller(installer: String?): String =
    if (installer.isNullOrBlank()) "tidak tercatat (adb, bawaan sistem, atau tidak diketahui)"
    else INSTALLERS[installer]?.let { "$it ($installer)" } ?: installer

/** Notes comparing an APK file with the installed copy of the same package. */
fun authenticityNotes(label: String, fileSigners: List<String>, fileVersionCode: Long, installed: InstalledCopy): List<SecurityNote> = buildList {
    val from = "Terpasang di perangkat: versi ${installed.versionName} (${installed.versionCode}), dari ${describeInstaller(installed.installer)}."
    when {
        fileSigners.isEmpty() -> add(SecurityNote(NoteLevel.INFO, "Keaslian tidak dapat dibandingkan",
            "File ini tidak memiliki tanda tangan yang terbaca, sehingga tidak dapat dicocokkan dengan $label yang terpasang. $from"))
        fileSigners.any { it in installed.signers } -> add(SecurityNote(NoteLevel.BAIK, "Penandatangan sama dengan aplikasi terpasang",
            "File ini ditandatangani kunci yang sama dengan $label di perangkat, jadi berasal dari pemilik kunci yang sama. $from"))
        fileSigners.any { it in installed.pastSigners } -> add(SecurityNote(NoteLevel.INFO, "Ditandatangani kunci lama aplikasi terpasang",
            "Kunci file ini ada di riwayat rotasi kunci $label yang terpasang: kemungkinan versi lama yang asli, dibuat sebelum kunci diganti. $from"))
        else -> add(SecurityNote(NoteLevel.PERHATIAN, "Penandatangan berbeda dari aplikasi terpasang",
            "Package sama, tetapi kunci penandatangan berbeda dari $label di perangkat. Kemungkinan APK hasil modifikasi (mod), build lain dari pengembang " +
                "(mis. versi F-Droid vs Play), atau palsu. Android menolak memasangnya sebagai pembaruan tanpa menghapus aplikasi lama dan datanya. $from"))
    }
    if (fileVersionCode > 0 && installed.versionCode > 0) when {
        fileVersionCode < installed.versionCode -> add(SecurityNote(NoteLevel.INFO, "Versi file lebih lama dari yang terpasang",
            "versionCode $fileVersionCode < ${installed.versionCode}. Android tidak mengizinkan downgrade biasa; aplikasi harus dihapus dulu."))
        fileVersionCode == installed.versionCode -> add(SecurityNote(NoteLevel.INFO, "Versi sama dengan yang terpasang",
            "versionCode $fileVersionCode. Bila penandatangan juga sama, isi kodenya semestinya identik dengan yang terpasang."))
        else -> add(SecurityNote(NoteLevel.INFO, "Versi file lebih baru dari yang terpasang",
            "versionCode $fileVersionCode > ${installed.versionCode}. File ini bisa menjadi pembaruan hanya jika penandatangannya sama."))
    }
}

/** Authenticity notes for this report: against the installed copy for a file, or where an installed app came from. */
fun AnatomyReport.authenticityNotes(): List<SecurityNote> = when {
    installedCopy != null -> authenticityNotes(label, signing.certificates.map { it.sha256 }, versionCode, installedCopy)
    installer != null || source == "Aplikasi terinstal" -> listOf(SecurityNote(NoteLevel.INFO, "Sumber pemasangan",
        "Dipasang dari ${describeInstaller(installer)}. Aplikasi dari toko resmi melewati pemeriksaan toko tersebut; file APK dari sumber lain sebaiknya dicek penandatangannya."))
    else -> emptyList()
}
