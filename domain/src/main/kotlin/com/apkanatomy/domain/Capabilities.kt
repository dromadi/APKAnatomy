package com.apkanatomy.domain

data class FeatureUse(val name: String, val required: Boolean)
data class QueriesDecl(val packages: List<String> = emptyList(), val intents: List<String> = emptyList(), val providers: List<String> = emptyList()) {
    val isEmpty: Boolean get() = packages.isEmpty() && intents.isEmpty() && providers.isEmpty()
}
data class ForegroundServiceUse(val service: String, val types: List<String>)
/** One rule of a backup configuration; [section] is cloud-backup, device-transfer or full-backup-content. */
data class BackupRule(val section: String, val include: Boolean, val domain: String, val path: String)
data class TaskAffinityUse(val activity: String, val affinity: String, val allowReparenting: Boolean, val exported: Boolean)

/** What the app asks of the device and the platform beyond components and permissions. */
data class Capabilities(
    val features: List<FeatureUse> = emptyList(),
    /** OpenGL ES version required, e.g. "3.1". */
    val glEsVersion: String? = null,
    val queries: QueriesDecl = QueriesDecl(),
    /** Shared libraries as (name, required). */
    val libraries: List<Pair<String, Boolean>> = emptyList(),
    val foregroundServices: List<ForegroundServiceUse> = emptyList(),
    val sharedUserId: String? = null,
    val requestLegacyExternalStorage: Boolean = false,
    /** Files holding backup rules (dataExtractionRules, fullBackupContent), as paths in the APK. */
    val backupRuleFiles: List<String> = emptyList(),
    val backupRules: List<BackupRule> = emptyList(),
    /** Activities whose taskAffinity points outside the app's own package. */
    val foreignTaskAffinities: List<TaskAffinityUse> = emptyList(),
    val grantUriProviders: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
)

// Bits of android:foregroundServiceType, verified against aapt2 for the public SDK.
private val FOREGROUND_TYPES = listOf(0x1 to "dataSync", 0x2 to "mediaPlayback", 0x4 to "phoneCall", 0x8 to "location",
    0x10 to "connectedDevice", 0x20 to "mediaProjection", 0x40 to "camera", 0x80 to "microphone", 0x100 to "health",
    0x200 to "remoteMessaging", 0x400 to "systemExempted", 0x800 to "shortService", 0x2000 to "mediaProcessing", 0x40000000 to "specialUse")

/** Names of the bits set in a compiled foregroundServiceType value; unknown bits are kept as hex. */
fun foregroundTypes(flags: Int): List<String> {
    val known = FOREGROUND_TYPES.filter { (bit, _) -> flags and bit != 0 }
    val rest = FOREGROUND_TYPES.fold(flags) { acc, (bit, _) -> acc and bit.inv() }
    return known.map { it.second } + listOfNotNull(rest.takeIf { it != 0 }?.let { "0x%x".format(it) })
}

fun foregroundTypeLesson(type: String): String? = when (type) {
    "location" -> "Melacak lokasi saat berjalan di latar depan (navigasi, olahraga)."
    "camera", "microphone" -> "Memakai $type terus-menerus walau layar aplikasi tidak dibuka."
    "mediaProjection" -> "Merekam atau membagikan isi layar."
    "dataSync" -> "Unggah/unduh data yang lama; dibatasi waktunya mulai Android 15."
    "mediaPlayback" -> "Memutar audio/video di latar."
    "phoneCall" -> "Panggilan telepon atau VoIP yang sedang berlangsung."
    "health" -> "Aplikasi kebugaran atau kesehatan yang membaca sensor."
    "specialUse" -> "Jenis khusus; alasan wajib dijelaskan ke Google Play."
    "shortService" -> "Pekerjaan singkat yang harus selesai dalam waktu terbatas."
    else -> null
}

fun featureLesson(name: String): String? = when {
    name == "android.hardware.camera" || name == "android.hardware.camera.any" -> "Butuh kamera."
    name.startsWith("android.hardware.camera.") -> "Butuh kemampuan kamera tertentu."
    name == "android.hardware.nfc" || name.startsWith("android.hardware.nfc.") -> "NFC (pembayaran, kartu, tag)."
    name.startsWith("android.hardware.bluetooth") -> "Bluetooth."
    name.startsWith("android.hardware.location") -> "Lokasi/GPS."
    name.startsWith("android.hardware.telephony") -> "Jaringan seluler/telepon."
    name.startsWith("android.hardware.sensor.") -> "Sensor ${name.substringAfterLast('.')}."
    name == "android.software.leanback" -> "Android TV."
    name == "android.hardware.type.watch" -> "Wear OS (jam pintar)."
    name == "android.hardware.type.automotive" -> "Android Automotive (mobil)."
    name.startsWith("android.hardware.vr") -> "VR."
    name == "android.hardware.touchscreen" -> "Layar sentuh (biasanya false agar bisa dipasang di TV/Chromebook)."
    else -> null
}

/** Security-relevant observations for the DNA notes. */
fun Capabilities.securityNotes(): List<SecurityNote> = buildList {
    foreignTaskAffinities.filter { it.exported && it.allowReparenting }.forEach {
        add(SecurityNote(NoteLevel.PERHATIAN, "Pola task hijacking (StrandHogg)", "${it.activity} memakai taskAffinity \"${it.affinity}\" dengan allowTaskReparenting. " +
            "Pola ini dapat menyisipkan layar ke task aplikasi lain sehingga tampak seperti milik aplikasi itu; perlu ditelusuri tujuannya."))
    }
    foreignTaskAffinities.filterNot { it.exported && it.allowReparenting }.takeIf { it.isNotEmpty() }?.let { list ->
        add(SecurityNote(NoteLevel.INFO, "taskAffinity ke task lain", list.joinToString { "${it.activity} → \"${it.affinity}\"" } + ". Biasanya untuk mengatur tumpukan layar, tetapi layak ditelusuri."))
    }
    sharedUserId?.let { add(SecurityNote(NoteLevel.INFO, "sharedUserId: $it", "Berbagi user ID Linux dengan aplikasi lain yang ditandatangani kunci sama, termasuk akses ke file privatnya. Fitur ini usang sejak Android 10.")) }
    if (requestLegacyExternalStorage) add(SecurityNote(NoteLevel.INFO, "requestLegacyExternalStorage", "Meminta akses penyimpanan model lama (sebelum scoped storage). Hanya berlaku untuk target SDK 29; diabaikan pada target 30 ke atas."))
}
