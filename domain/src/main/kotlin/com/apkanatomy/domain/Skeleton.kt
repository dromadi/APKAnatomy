package com.apkanatomy.domain

private const val ACTION = "android.intent.action."
private const val CATEGORY = "android.intent.category."

/** A VIEW filter with a link scheme: other apps or the browser can open this component through a URI. */
val IntentFilterInfo.isDeepLink: Boolean
    get() = ACTION + "VIEW" in actions && schemes.any { it.lowercase() !in setOf("file", "content") }

/** What an intent-filter means for someone studying the app, or null when it is not a well-known pattern. */
fun IntentFilterInfo.lesson(): String? {
    fun has(action: String) = actions.any { it == ACTION + action }
    fun cat(category: String) = categories.any { it == CATEGORY + category }
    val web = schemes.any { it.lowercase() == "http" || it.lowercase() == "https" }
    return when {
        has("MAIN") && cat("LAUNCHER") -> "Titik masuk launcher: ikon aplikasi membuka komponen ini. Wajar bila exported tanpa izin."
        isDeepLink && cat("BROWSABLE") && web && autoVerify -> "App Link (autoVerify): tautan web domain ini dapat langsung dibuka aplikasi setelah domainnya terverifikasi."
        isDeepLink && cat("BROWSABLE") && web -> "Deep link web: tautan http/https dapat menawarkan aplikasi ini sebagai pembuka."
        has("VIEW") && (mimeTypes.isNotEmpty() || schemes.isNotEmpty()) && !isDeepLink -> "Pembuka file: menawarkan diri untuk membuka berkas atau konten jenis ini."
        isDeepLink -> "Deep link: URI dengan skema ini dapat membuka komponen dari aplikasi lain atau browser."
        has("SEND") || has("SEND_MULTIPLE") -> "Target berbagi: muncul di menu Bagikan untuk data yang cocok."
        has("BOOT_COMPLETED") || has("LOCKED_BOOT_COMPLETED") -> "Dipicu saat perangkat selesai menyala (boot)."
        has("PACKAGE_ADDED") || has("PACKAGE_REMOVED") || has("PACKAGE_REPLACED") -> "Dipicu saat aplikasi lain dipasang, dihapus, atau diperbarui."
        actions.any { it.startsWith("android.provider.Telephony.SMS") } -> "Menerima siaran terkait SMS."
        actions.any { it == "com.google.firebase.MESSAGING_EVENT" } -> "Menerima pesan push Firebase Cloud Messaging."
        else -> null
    }
}

/** Special role a component plays, recognised from the permission the system uses to bind it. */
fun AppComponent.role(): String? = when (permission) {
    "android.permission.BIND_ACCESSIBILITY_SERVICE" -> "Layanan aksesibilitas: setelah diaktifkan pengguna, dapat membaca isi layar dan melakukan aksi atas nama pengguna."
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> "Pendengar notifikasi: setelah diizinkan, dapat membaca semua notifikasi yang masuk."
    "android.permission.BIND_DEVICE_ADMIN" -> "Admin perangkat: setelah diaktifkan, dapat menerapkan kebijakan seperti mengunci layar atau menghapus data."
    "android.permission.BIND_INPUT_METHOD" -> "Keyboard (input method): bila dipilih, menerima semua teks yang diketik."
    "android.permission.BIND_VPN_SERVICE" -> "Layanan VPN: setelah disetujui, dapat melewatkan lalu lintas jaringan perangkat."
    "android.permission.BIND_AUTOFILL_SERVICE" -> "Layanan isi otomatis: dapat mengisi dan membaca kolom formulir di aplikasi lain."
    "android.permission.BIND_JOB_SERVICE" -> "Pekerjaan latar belakang yang dijadwalkan sistem (JobScheduler/WorkManager)."
    "android.permission.BIND_QUICK_SETTINGS_TILE" -> "Tile di panel Pengaturan Cepat."
    "android.permission.BIND_NFC_SERVICE" -> "Emulasi kartu NFC (mis. pembayaran atau kartu akses)."
    "android.permission.BIND_WALLPAPER" -> "Wallpaper hidup."
    else -> null
}

private val sensitivePermissions = mapOf(
    "android.permission.SYSTEM_ALERT_WINDOW" to "Menampilkan jendela di atas aplikasi lain (overlay).",
    "android.permission.MANAGE_EXTERNAL_STORAGE" to "Akses ke hampir semua file di penyimpanan bersama (All files access).",
    "android.permission.REQUEST_INSTALL_PACKAGES" to "Meminta pemasangan APK lain.",
    "android.permission.QUERY_ALL_PACKAGES" to "Melihat daftar semua aplikasi terpasang.",
    "android.permission.PACKAGE_USAGE_STATS" to "Membaca statistik penggunaan aplikasi (diberikan lewat Pengaturan).",
    "android.permission.WRITE_SETTINGS" to "Mengubah pengaturan sistem.",
    "android.permission.ACCESS_BACKGROUND_LOCATION" to "Mengakses lokasi saat aplikasi tidak sedang dibuka.",
    "android.permission.READ_SMS" to "Membaca SMS.", "android.permission.RECEIVE_SMS" to "Menerima SMS masuk.",
    "android.permission.SEND_SMS" to "Mengirim SMS.", "android.permission.READ_CALL_LOG" to "Membaca riwayat panggilan.",
    "android.permission.READ_CONTACTS" to "Membaca kontak.", "android.permission.RECORD_AUDIO" to "Merekam audio dari mikrofon.",
    "android.permission.CAMERA" to "Menggunakan kamera.", "android.permission.READ_PHONE_STATE" to "Membaca status dan identitas telepon.",
    "android.permission.BODY_SENSORS" to "Membaca sensor tubuh (mis. detak jantung).",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "Meminta dikecualikan dari penghematan baterai.",
    "android.permission.USE_FULL_SCREEN_INTENT" to "Menampilkan notifikasi layar penuh (mis. panggilan masuk).",
    "android.permission.SCHEDULE_EXACT_ALARM" to "Menjadwalkan alarm pada waktu tepat.",
    "android.permission.BIND_ACCESSIBILITY_SERVICE" to "Menyediakan layanan aksesibilitas.",
)

/** Why a requested permission deserves a closer look, or null for ordinary ones. */
fun sensitivePermissionLesson(name: String): String? = sensitivePermissions[name]

/**
 * URIs that this deep-link filter should accept, for trying it out: each scheme with each host (or none) and the
 * first literal path. Prefix paths ("/open*") lose the star; pattern paths are skipped. At most [max].
 */
fun IntentFilterInfo.exampleUris(max: Int = 4): List<String> {
    if (!isDeepLink) return emptyList()
    val path = paths.firstOrNull { !it.startsWith("pola ") && !it.startsWith("*") }?.removeSuffix("*").orEmpty()
    return schemes.flatMap { scheme ->
        hosts.ifEmpty { listOf("") }.map { host ->
"$scheme://${host.removePrefix("*.")}$path"
        }
    }.distinct().take(max)
}
