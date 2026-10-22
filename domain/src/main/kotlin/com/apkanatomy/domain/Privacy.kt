package com.apkanatomy.domain

/** A bundled SDK whose job involves collecting usage or device data. */
data class Tracker(val name: String, val purpose: String, val evidence: List<String>) : java.io.Serializable

/** A kind of personal data the app can ask for, with the permissions that grant it. */
data class DataAccess(val category: String, val explanation: String, val permissions: List<String>, val runtimePrompt: Boolean) : java.io.Serializable

data class PrivacyProfile(
    val trackers: List<Tracker>,
    val dataAccess: List<DataAccess>,
    /** Hosts of known ad/analytics/attribution services found in the app's strings. */
    val trackerHosts: List<String>,
    val usesAdvertisingId: Boolean,
    val summary: String,
) : java.io.Serializable

private val TRACKER_PURPOSE = mapOf(
    "Google AdMob" to "Iklan", "Meta Audience Network" to "Iklan", "AppLovin" to "Iklan", "Unity Ads" to "Iklan", "ironSource" to "Iklan",
    "Pangle" to "Iklan", "Mintegral" to "Iklan", "Vungle / Liftoff" to "Iklan",
    "AppsFlyer" to "Atribusi pemasaran", "Adjust" to "Atribusi pemasaran",
    "Firebase Analytics" to "Analitik", "Amplitude" to "Analitik", "Mixpanel" to "Analitik", "Facebook SDK" to "Analitik & login sosial",
    "Firebase Crashlytics" to "Pelaporan crash", "Sentry" to "Pelaporan crash", "Bugsnag" to "Pelaporan crash",
    "OneSignal" to "Notifikasi & segmentasi",
)
private val TRACKER_SERVICES = setOf("Iklan Google", "Google/Firebase Analytics", "Google Analytics", "Crashlytics", "Meta", "AppLovin", "Unity",
    "AppsFlyer", "Adjust", "OneSignal", "Sentry")

private class Category(val name: String, val explanation: String, val permissions: Set<String>, val runtime: Boolean = true)
private const val P = "android.permission."
private val CATEGORIES = listOf(
    Category("Lokasi", "Posisi perangkat; lokasi presisi atau perkiraan.", setOf("${P}ACCESS_FINE_LOCATION", "${P}ACCESS_COARSE_LOCATION")),
    Category("Lokasi di latar belakang", "Lokasi saat aplikasi tidak dibuka.", setOf("${P}ACCESS_BACKGROUND_LOCATION")),
    Category("Kamera", "Foto dan video dari kamera.", setOf("${P}CAMERA")),
    Category("Mikrofon", "Rekaman suara.", setOf("${P}RECORD_AUDIO")),
    Category("Kontak & akun", "Daftar kontak dan akun di perangkat.", setOf("${P}READ_CONTACTS", "${P}WRITE_CONTACTS", "${P}GET_ACCOUNTS")),
    Category("Kalender", "Acara kalender.", setOf("${P}READ_CALENDAR", "${P}WRITE_CALENDAR")),
    Category("SMS", "Pesan teks.", setOf("${P}READ_SMS", "${P}RECEIVE_SMS", "${P}SEND_SMS", "${P}RECEIVE_MMS")),
    Category("Telepon & log panggilan", "Status telepon, nomor, dan riwayat panggilan.", setOf("${P}READ_PHONE_STATE", "${P}READ_PHONE_NUMBERS", "${P}CALL_PHONE", "${P}READ_CALL_LOG", "${P}WRITE_CALL_LOG", "${P}ANSWER_PHONE_CALLS")),
    Category("Foto, video & audio", "Media di penyimpanan bersama.", setOf("${P}READ_MEDIA_IMAGES", "${P}READ_MEDIA_VIDEO", "${P}READ_MEDIA_AUDIO", "${P}READ_MEDIA_VISUAL_USER_SELECTED", "${P}READ_EXTERNAL_STORAGE", "${P}WRITE_EXTERNAL_STORAGE")),
    Category("Semua file", "Hampir semua file di penyimpanan bersama; diberikan lewat Pengaturan.", setOf("${P}MANAGE_EXTERNAL_STORAGE"), runtime = false),
    Category("Kesehatan & aktivitas", "Sensor tubuh dan aktivitas fisik.", setOf("${P}BODY_SENSORS", "${P}BODY_SENSORS_BACKGROUND", "${P}ACTIVITY_RECOGNITION")),
    Category("Perangkat sekitar", "Bluetooth dan Wi-Fi di sekitar (dapat menyiratkan lokasi).", setOf("${P}BLUETOOTH_SCAN", "${P}BLUETOOTH_CONNECT", "${P}NEARBY_WIFI_DEVICES", "${P}UWB_RANGING")),
    Category("Aplikasi terpasang", "Daftar aplikasi lain di perangkat.", setOf("${P}QUERY_ALL_PACKAGES"), runtime = false),
    Category("Statistik penggunaan", "Kapan dan berapa lama aplikasi lain dipakai; diberikan lewat Pengaturan.", setOf("${P}PACKAGE_USAGE_STATS"), runtime = false),
    Category("ID iklan", "Advertising ID untuk iklan dan atribusi lintas aplikasi.", setOf("com.google.android.gms.permission.AD_ID"), runtime = false),
)

/** What the app could learn about its user, from static evidence only. */
fun AnatomyReport.privacyProfile(): PrivacyProfile {
    val requested = permissions.map { it.name }.toSet()
    val trackers = sdks.mapNotNull { sdk -> TRACKER_PURPOSE[sdk.name]?.let { Tracker(sdk.name, it, sdk.evidence) } }
    val access = CATEGORIES.mapNotNull { c ->
        val used = c.permissions.filter { it in requested }
        if (used.isEmpty()) null else DataAccess(c.name, c.explanation, used.sorted(), c.runtime)
    }
    val hosts = traces.hosts.filter { it.service in TRACKER_SERVICES }.map { it.host }
    val adId = "com.google.android.gms.permission.AD_ID" in requested
    val summary = buildString {
        append(if (trackers.isEmpty()) "Tidak ada SDK pelacak yang dikenali." else "${trackers.size} SDK pelacak dibundel (${trackers.map { it.purpose }.distinct().joinToString()}).")
        append(" ")
        append(if (access.isEmpty()) "Tidak meminta akses ke kategori data pribadi yang umum." else "Dapat meminta akses ke: ${access.joinToString { it.category.lowercase() }}.")
        if (adId) append(" Membaca ID iklan.")
    }
    return PrivacyProfile(trackers, access, hosts, adId, summary)
}
