package com.apkanatomy.domain

/** A behaviour seen in the code: calls to one group of sensitive APIs, or a pattern such as a trust-all TrustManager. */
data class BehaviorFinding(
    val id: String, val category: String, val title: String, val level: NoteLevel, val lesson: String,
    /** The APIs or patterns that matched, e.g. "WebView.addJavascriptInterface". */
    val apis: List<String>,
    /** Up to a few method IDs where it was seen. */
    val usedBy: List<String>,
    /** Call sites (or methods, for patterns) in total, and how many sit in the app's own package. */
    val count: Int, val inAppPackage: Int,
) : java.io.Serializable

data class BehaviorReport(
    val findings: List<BehaviorFinding> = emptyList(),
    val scannedInstructions: Int = 0,
    val notes: List<String> = emptyList(),
) : java.io.Serializable {
    val categories: List<String> get() = findings.map { it.category }.distinct()
}

/**
 * One rule. A call rule matches invocations of [owner] (a type descriptor) named [method] (any name when null);
 * when [string] is set, the calling method must also load a matching const-string, e.g. "AES/ECB/..." for Cipher.getInstance.
 * A string-only rule ([owner] null) matches methods that load such a string.
 */
private class BehaviorRule(val id: String, val category: String, val title: String, val level: NoteLevel, val lesson: String,
    val owner: String?, val method: Set<String>?, val string: ((String) -> Boolean)? = null,
    /** A condition on all strings the method loads, for rules a single string cannot decide. */
    val strings: ((List<String>) -> Boolean)? = null) {
    /** "Landroid/provider/Settings$Secure;" → "Settings.Secure". */
    val ownerName: String get() = owner.orEmpty().removePrefix("L").removeSuffix(";").substringAfterLast('/').replace('$', '.')
}

private const val DYNAMIC = "Kode dinamis"
private const val WEB = "WebView"
private const val TLS = "Koneksi aman (TLS)"
private const val CRYPTO = "Kriptografi"
private const val IDENTITY = "Identitas perangkat"
private const val SENSORS = "Lokasi, kamera & mikrofon"
private const val PRIVATE_DATA = "Data pribadi"
private const val PROTECTION = "Proteksi aplikasi"

private val RULES = listOf(
    BehaviorRule("dex-loader", DYNAMIC, "Memuat kode DEX saat berjalan", NoteLevel.INFO,
        "DexClassLoader/InMemoryDexClassLoader menjalankan kode yang tidak ada saat APK diperiksa (plugin, pembaruan diam-diam, atau modul unduhan). Kode itu tidak terlihat oleh analisis statis ini.",
        "Ldalvik/system/DexClassLoader;", setOf("<init>")),
    BehaviorRule("dex-memory", DYNAMIC, "Memuat kode DEX dari memori", NoteLevel.INFO,
        "InMemoryDexClassLoader memuat DEX dari byte array, misalnya hasil dekripsi atau unduhan; pola ini sering dipakai packer dan proteksi.",
        "Ldalvik/system/InMemoryDexClassLoader;", setOf("<init>")),
    BehaviorRule("reflection", DYNAMIC, "Reflection", NoteLevel.INFO,
        "Class.forName dan Method.invoke memanggil kode berdasarkan nama string. Umum di library (serialisasi, DI), tetapi membuat jalur pemanggilan tidak terlihat di Find usage.",
        "Ljava/lang/Class;", setOf("forName", "getDeclaredMethod")),
    BehaviorRule("reflection", DYNAMIC, "Reflection", NoteLevel.INFO, "", "Ljava/lang/reflect/Method;", setOf("invoke")),
    BehaviorRule("exec", DYNAMIC, "Menjalankan perintah shell", NoteLevel.INFO,
        "Runtime.exec/ProcessBuilder menjalankan program lain di perangkat (mis. getprop, logcat, atau su untuk cek root). Perhatikan perintah yang dimuat di method tersebut.",
        "Ljava/lang/Runtime;", setOf("exec")),
    BehaviorRule("exec", DYNAMIC, "Menjalankan perintah shell", NoteLevel.INFO, "", "Ljava/lang/ProcessBuilder;", setOf("start")),
    BehaviorRule("native-load", DYNAMIC, "Memuat library native", NoteLevel.INFO,
        "System.loadLibrary/load memuat file .so; lihat bagian native di tab Teknologi untuk isinya.",
        "Ljava/lang/System;", setOf("loadLibrary", "load")),

    BehaviorRule("js-bridge", WEB, "Jembatan JavaScript ke kode aplikasi", NoteLevel.INFO,
        "addJavascriptInterface memberi halaman web akses ke method Java/Kotlin bertanda @JavascriptInterface. Aman bila hanya memuat halaman tepercaya lewat HTTPS.",
        "Landroid/webkit/WebView;", setOf("addJavascriptInterface")),
    BehaviorRule("js-enabled", WEB, "JavaScript di WebView diaktifkan", NoteLevel.INFO,
        "Lazim untuk aplikasi hybrid; risikonya naik bila WebView memuat konten yang tidak tepercaya.",
        "Landroid/webkit/WebSettings;", setOf("setJavaScriptEnabled")),
    BehaviorRule("file-url-access", WEB, "WebView boleh membaca file dari halaman file://", NoteLevel.PERHATIAN,
        "setAllowFileAccessFromFileURLs/setAllowUniversalAccessFromFileURLs mengizinkan halaman lokal membaca file lain atau situs mana pun; bila diaktifkan, HTML berbahaya dapat mencuri data aplikasi.",
        "Landroid/webkit/WebSettings;", setOf("setAllowFileAccessFromFileURLs", "setAllowUniversalAccessFromFileURLs")),
    BehaviorRule("ssl-proceed", TLS, "Mengabaikan error sertifikat di WebView", NoteLevel.PERHATIAN,
        "SslErrorHandler.proceed() melanjutkan pemuatan meski sertifikat tidak sah, sehingga lalu lintas WebView bisa disadap (man-in-the-middle). Google Play menolak aplikasi dengan pola ini.",
        "Landroid/webkit/SslErrorHandler;", setOf("proceed")),
    BehaviorRule("pinning", TLS, "Certificate pinning (OkHttp)", NoteLevel.BAIK,
        "CertificatePinner membatasi sertifikat server yang dipercaya, sehingga penyadapan dengan CA palsu lebih sulit.",
        "Lokhttp3/CertificatePinner\$Builder;", setOf("add")),

    BehaviorRule("ecb", CRYPTO, "Enkripsi mode ECB", NoteLevel.PERHATIAN,
        "Cipher.getInstance(\"AES\") tanpa mode, atau \"AES/ECB/...\", memakai mode ECB: blok data yang sama menghasilkan ciphertext yang sama, sehingga pola isi tetap terlihat. Gunakan AES/GCM. " +
            "Deteksi ini memakai string di method yang sama, jadi periksa instruksinya.",
        "Ljavax/crypto/Cipher;", setOf("getInstance"),
        strings = { list -> list.any { it.uppercase().startsWith("AES/ECB") } || "AES" in list && list.none { it.uppercase().startsWith("AES/") } }),
    BehaviorRule("des", CRYPTO, "Algoritma enkripsi usang (DES/RC4)", NoteLevel.PERHATIAN,
        "DES, 3DES, dan RC4 dianggap tidak aman lagi untuk data baru.",
        "Ljavax/crypto/Cipher;", setOf("getInstance"), { it.uppercase().let { u -> u == "DES" || u.startsWith("DES/") || u.startsWith("DESEDE") || u.startsWith("RC4") || u == "ARCFOUR" } }),
    BehaviorRule("weak-hash", CRYPTO, "Hash MD5/SHA-1", NoteLevel.INFO,
        "MD5 dan SHA-1 tidak lagi aman untuk tanda tangan atau kata sandi, tetapi masih wajar untuk checksum atau ID cache.",
        "Ljava/security/MessageDigest;", setOf("getInstance"), { it.uppercase() in setOf("MD5", "SHA-1", "SHA1") }),
    BehaviorRule("keystore", CRYPTO, "Kunci disimpan di Android Keystore", NoteLevel.BAIK,
        "KeyGenParameterSpec membuat kunci di Android Keystore (bisa didukung hardware), sehingga kunci tidak pernah keluar dari perangkat dalam bentuk mentah.",
        "Landroid/security/keystore/KeyGenParameterSpec\$Builder;", setOf("<init>")),

    BehaviorRule("device-id", IDENTITY, "Membaca ID telepon/SIM", NoteLevel.INFO,
        "IMEI, nomor SIM, atau nomor telepon adalah pengenal permanen. Sejak Android 10 hanya aplikasi istimewa yang boleh membacanya.",
        "Landroid/telephony/TelephonyManager;", setOf("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber", "getLine1Number")),
    BehaviorRule("android-id", IDENTITY, "Membaca ANDROID_ID", NoteLevel.INFO,
        "ANDROID_ID unik per aplikasi, pengguna, dan perangkat; dipakai untuk mengenali perangkat tanpa izin khusus.",
        "Landroid/provider/Settings\$Secure;", setOf("getString"), { it == "android_id" }),
    BehaviorRule("ad-id", IDENTITY, "Membaca Advertising ID", NoteLevel.INFO,
        "ID iklan dipakai untuk penargetan iklan; pengguna dapat meresetnya atau menghapusnya di pengaturan.",
        "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;", setOf("getAdvertisingIdInfo")),
    BehaviorRule("installed-apps", IDENTITY, "Mendaftar aplikasi terinstal", NoteLevel.INFO,
        "Daftar aplikasi dapat membentuk sidik jari perangkat dan mengungkap minat pengguna; sejak Android 11 dibatasi oleh deklarasi <queries>.",
        "Landroid/content/pm/PackageManager;", setOf("getInstalledPackages", "getInstalledApplications")),

    BehaviorRule("location", SENSORS, "Membaca lokasi", NoteLevel.INFO,
        "Kode meminta pembaruan atau lokasi terakhir; berjalan hanya bila izin lokasi diberikan.",
        "Landroid/location/LocationManager;", setOf("requestLocationUpdates", "getLastKnownLocation", "getCurrentLocation")),
    BehaviorRule("location", SENSORS, "Membaca lokasi", NoteLevel.INFO, "",
        "Lcom/google/android/gms/location/FusedLocationProviderClient;", setOf("requestLocationUpdates", "getLastLocation", "getCurrentLocation")),
    BehaviorRule("camera", SENSORS, "Membuka kamera", NoteLevel.INFO, "Kode membuka kamera secara langsung (bukan lewat aplikasi kamera lain).",
        "Landroid/hardware/camera2/CameraManager;", setOf("openCamera")),
    BehaviorRule("camera", SENSORS, "Membuka kamera", NoteLevel.INFO, "", "Landroid/hardware/Camera;", setOf("open")),
    BehaviorRule("microphone", SENSORS, "Merekam audio", NoteLevel.INFO, "AudioRecord/MediaRecorder dengan sumber mikrofon merekam suara.",
        "Landroid/media/AudioRecord;", setOf("startRecording")),
    BehaviorRule("microphone", SENSORS, "Merekam audio", NoteLevel.INFO, "", "Landroid/media/MediaRecorder;", setOf("setAudioSource")),
    BehaviorRule("screen-capture", SENSORS, "Merekam layar", NoteLevel.INFO, "MediaProjection menangkap isi layar setelah pengguna menyetujui dialog sistem.",
        "Landroid/media/projection/MediaProjectionManager;", setOf("createScreenCaptureIntent", "getMediaProjection")),

    BehaviorRule("clipboard", PRIVATE_DATA, "Membaca clipboard", NoteLevel.INFO,
        "Clipboard bisa berisi kata sandi atau kode OTP. Sejak Android 12 sistem menampilkan notifikasi saat aplikasi membacanya.",
        "Landroid/content/ClipboardManager;", setOf("getPrimaryClip", "getText")),
    BehaviorRule("sms-send", PRIVATE_DATA, "Mengirim SMS", NoteLevel.PERHATIAN,
        "SmsManager mengirim SMS tanpa membuka aplikasi pesan; bisa menimbulkan biaya. Pastikan memang fitur inti aplikasi.",
        "Landroid/telephony/SmsManager;", setOf("sendTextMessage", "sendMultipartTextMessage", "sendDataMessage")),
    BehaviorRule("contacts-query", PRIVATE_DATA, "Membaca kontak/SMS lewat ContentResolver", NoteLevel.INFO,
        "Query ke content://contacts, content://sms, atau call log membaca data pribadi (butuh izin runtime).",
        "Landroid/content/ContentResolver;", setOf("query"), { it.startsWith("content://sms") || it.startsWith("content://com.android.contacts") || it.startsWith("content://call_log") }),

    BehaviorRule("root-check", PROTECTION, "Deteksi root", NoteLevel.INFO,
        "String seperti /system/xbin/su atau com.topjohnwu.magisk menandakan aplikasi memeriksa apakah perangkat di-root (umum di aplikasi bank dan game).",
        null, null, { it == "/system/xbin/su" || it == "/system/bin/su" || it == "/sbin/su" || it == "com.topjohnwu.magisk" || it == "eu.chainfire.supersu" || it == "/system/app/Superuser.apk" || it == "test-keys" }),
    BehaviorRule("debugger-check", PROTECTION, "Deteksi debugger", NoteLevel.INFO,
        "Debug.isDebuggerConnected dipakai untuk menolak berjalan saat sedang di-debug.",
        "Landroid/os/Debug;", setOf("isDebuggerConnected", "waitingForDebugger")),
    BehaviorRule("integrity", PROTECTION, "Play Integrity / SafetyNet", NoteLevel.BAIK,
        "Aplikasi meminta token integritas dari Google Play untuk memastikan berjalan di perangkat dan APK asli.",
        "Lcom/google/android/play/core/integrity/IntegrityManager;", setOf("requestIntegrityToken")),
    BehaviorRule("integrity", PROTECTION, "Play Integrity / SafetyNet", NoteLevel.BAIK, "",
        "Lcom/google/android/gms/safetynet/SafetyNetClient;", setOf("attest")),
    BehaviorRule("biometric", PROTECTION, "Autentikasi biometrik", NoteLevel.INFO,
        "BiometricPrompt meminta sidik jari atau wajah; data biometrik sendiri tidak pernah diterima aplikasi.",
        "Landroidx/biometric/BiometricPrompt;", setOf("authenticate")),
    BehaviorRule("biometric", PROTECTION, "Autentikasi biometrik", NoteLevel.INFO, "", "Landroid/hardware/biometrics/BiometricPrompt;", setOf("authenticate")),
)

/** Patterns found by looking at whole methods rather than calls; reported through [BehaviorCollector.pattern]. */
object BehaviorPatterns {
    const val TRUST_ALL = "trust-all"
    const val HOSTNAME_ALL = "hostname-all"
    internal val INFO = mapOf(
        TRUST_ALL to Triple("TrustManager yang menerima semua sertifikat", "X509TrustManager.checkServerTrusted",
            "checkServerTrusted yang langsung kembali tanpa memeriksa apa pun membuat HTTPS bisa disadap dengan sertifikat palsu (man-in-the-middle)."),
        HOSTNAME_ALL to Triple("HostnameVerifier yang menerima semua host", "HostnameVerifier.verify",
            "verify yang selalu mengembalikan true menerima sertifikat milik domain lain, sehingga HTTPS bisa disadap."),
    )
}

/**
 * Collects behaviours while the DEX files are read. [method] is called once per method with the calls it
 * makes (owner descriptor, name) and the strings it loads; [pattern] reports whole-method patterns.
 */
class BehaviorCollector(private val appPackage: String? = null, private val maxUsedBy: Int = 5) {
    private class Acc(val id: String, val category: String, val title: String, val level: NoteLevel, var lesson: String) {
        val apis = linkedSetOf<String>(); val appUsedBy = linkedSetOf<String>(); val otherUsedBy = linkedSetOf<String>(); var count = 0; var inApp = 0
    }
    private val byOwner: Map<String, List<BehaviorRule>> = RULES.filter { it.owner != null }.groupBy { it.owner!! }
    private val stringRules = RULES.filter { it.owner == null }
    private val found = LinkedHashMap<String, Acc>()
    private val notes = linkedSetOf<String>()
    private val appPrefix = appPackage?.takeIf { it.isNotBlank() }?.let { "L" + it.replace('.', '/') + "/" }
    var scannedInstructions = 0
        private set

    /** True when a call to [owner] could match a rule, so the scanner only keeps calls worth reporting. */
    fun watches(owner: String): Boolean = owner in byOwner

    fun method(methodId: String, calls: List<Pair<String, String>>, strings: List<String>, instructions: Int) {
        scannedInstructions += instructions
        for ((owner, name) in calls) {
            for (rule in byOwner[owner].orEmpty()) {
                if (rule.method != null && name !in rule.method) continue
                if (rule.string != null && strings.none(rule.string)) continue
                if (rule.strings != null && !rule.strings.invoke(strings)) continue
                add(rule.id, rule.category, rule.title, rule.level, rule.lesson, "${rule.ownerName}.$name", methodId)
            }
        }
        if (strings.isNotEmpty()) for (rule in stringRules) strings.firstOrNull(rule.string!!)?.let {
            add(rule.id, rule.category, rule.title, rule.level, rule.lesson, "\"$it\"", methodId)
        }
    }

    fun pattern(id: String, methodId: String) {
        val (title, api, lesson) = BehaviorPatterns.INFO[id] ?: return
        add(id, TLS, title, NoteLevel.PERHATIAN, lesson, api, methodId)
    }

    fun note(text: String) { notes += text }

    private fun add(id: String, category: String, title: String, level: NoteLevel, lesson: String, api: String, methodId: String) {
        val acc = found.getOrPut(id) { Acc(id, category, title, level, lesson) }
        if (acc.lesson.isEmpty()) acc.lesson = lesson
        acc.apis += api
        acc.count++
        // Methods in the app's own package are listed first.
        val inApp = appPrefix != null && methodId.substringAfter(':').substringAfter(':').startsWith(appPrefix)
        if (inApp) acc.inApp++
        val target = if (inApp) acc.appUsedBy else acc.otherUsedBy
        if (target.size < maxUsedBy) target += methodId
    }

    fun result(): BehaviorReport = BehaviorReport(
        findings = found.values.map { BehaviorFinding(it.id, it.category, it.title, it.level, it.lesson, it.apis.toList(), (it.appUsedBy + it.otherUsedBy).take(maxUsedBy), it.count, it.inApp) }
            .sortedWith(compareBy({ CATEGORY_ORDER.indexOf(it.category) }, { it.level != NoteLevel.PERHATIAN }, { it.title })),
        scannedInstructions = scannedInstructions,
        notes = notes.toList(),
    )

    private companion object {
        val CATEGORY_ORDER = listOf(TLS, WEB, CRYPTO, PRIVATE_DATA, SENSORS, IDENTITY, DYNAMIC, PROTECTION)
    }
}

/** Behaviours the DNA tab should raise: everything marked PERHATIAN. */
fun BehaviorReport.securityNotes(): List<SecurityNote> = findings.filter { it.level == NoteLevel.PERHATIAN }.map {
    SecurityNote(NoteLevel.PERHATIAN, "Perilaku: ${it.title}", it.lesson + " Ditemukan di ${it.count} tempat; lihat tab Perilaku.")
}
