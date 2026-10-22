package com.apkanatomy.domain

data class DetectedSdk(
    val name: String, val category: String, val explanation: String,
    /** Matching packages (at most a few) found in the DEX files. */
    val evidence: List<String>, val packageCount: Int,
    /** From a META-INF version file when the library ships one, e.g. "firebase-messaging 24.1.0". */
    val versions: List<String> = emptyList(),
) : java.io.Serializable

private class SdkRule(val name: String, val category: String, val explanation: String,
    val packages: List<String>, val artifacts: List<String> = emptyList())

private const val NET = "Jaringan"; private const val DATA = "Data & serialisasi"; private const val IMG = "Gambar & media"
private const val DI = "Arsitektur"; private const val FIREBASE = "Firebase"; private const val GOOGLE = "Layanan Google"
private const val ANALYTICS = "Analitik & pemantauan"; private const val ADS = "Iklan"; private const val SOCIAL = "Login & sosial"
private const val PAY = "Pembayaran"; private const val INTEGRITY = "Integritas perangkat"; private const val UI = "Antarmuka"

private val rules = listOf(
    SdkRule("OkHttp", NET, "Klien HTTP yang menangani koneksi, cache, dan TLS.", listOf("okhttp3", "com.squareup.okhttp")),
    SdkRule("Retrofit", NET, "Membuat klien REST API dari interface Kotlin/Java; biasanya di atas OkHttp.", listOf("retrofit2")),
    SdkRule("Ktor Client", NET, "Klien HTTP berbasis coroutine dari JetBrains.", listOf("io.ktor.client")),
    SdkRule("Volley", NET, "Pustaka jaringan lama dari Google.", listOf("com.android.volley")),
    SdkRule("gRPC", NET, "Panggilan RPC biner melalui HTTP/2.", listOf("io.grpc")),
    SdkRule("Gson", DATA, "Mengubah JSON menjadi objek dan sebaliknya.", listOf("com.google.gson")),
    SdkRule("Moshi", DATA, "Parser JSON modern dari Square.", listOf("com.squareup.moshi")),
    SdkRule("kotlinx.serialization", DATA, "Serialisasi berbasis plugin compiler Kotlin.", listOf("kotlinx.serialization")),
    SdkRule("Jackson", DATA, "Pustaka JSON dari ekosistem Java.", listOf("com.fasterxml.jackson")),
    SdkRule("Room", DATA, "Lapisan SQLite dari Jetpack dengan query yang diperiksa saat kompilasi.", listOf("androidx.room"), listOf("androidx.room:room-runtime")),
    SdkRule("Realm", DATA, "Database objek lokal.", listOf("io.realm")),
    SdkRule("SQLCipher", DATA, "SQLite dengan enkripsi file database.", listOf("net.sqlcipher", "net.zetetic")),
    SdkRule("ObjectBox", DATA, "Database objek berkinerja tinggi.", listOf("io.objectbox")),
    SdkRule("SQLDelight", DATA, "Menghasilkan API Kotlin dari query SQL.", listOf("app.cash.sqldelight", "com.squareup.sqldelight")),
    SdkRule("Glide", IMG, "Memuat dan menyimpan cache gambar.", listOf("com.bumptech.glide")),
    SdkRule("Picasso", IMG, "Pemuat gambar dari Square.", listOf("com.squareup.picasso")),
    SdkRule("Coil", IMG, "Pemuat gambar berbasis coroutine.", listOf("coil", "coil3")),
    SdkRule("Fresco", IMG, "Pemuat gambar dari Meta dengan manajemen memori sendiri.", listOf("com.facebook.fresco", "com.facebook.drawee")),
    SdkRule("ExoPlayer / Media3", IMG, "Pemutar audio/video, termasuk streaming.", listOf("com.google.android.exoplayer2", "androidx.media3"), listOf("androidx.media3:media3-exoplayer")),
    SdkRule("Lottie", UI, "Memutar animasi vektor dari After Effects.", listOf("com.airbnb.lottie")),
    SdkRule("Jetpack Compose", UI, "Toolkit UI deklaratif Android.", listOf("androidx.compose.runtime"), listOf("androidx.compose.runtime:runtime")),
    SdkRule("Dagger", DI, "Dependency injection yang dibuat saat kompilasi.", listOf("dagger")),
    SdkRule("Hilt", DI, "Dependency injection Jetpack di atas Dagger.", listOf("dagger.hilt")),
    SdkRule("Koin", DI, "Dependency injection ringan untuk Kotlin.", listOf("org.koin")),
    SdkRule("RxJava", DI, "Pemrograman reaktif dengan aliran data (Observable).", listOf("io.reactivex", "io.reactivex.rxjava3")),
    SdkRule("Kotlin Coroutines", DI, "Pekerjaan asinkron terstruktur di Kotlin.", listOf("kotlinx.coroutines")),
    SdkRule("Firebase Cloud Messaging", FIREBASE, "Menerima notifikasi push.", listOf("com.google.firebase.messaging"), listOf("com.google.firebase:firebase-messaging")),
    SdkRule("Firebase Crashlytics", FIREBASE, "Melaporkan crash ke developer.", listOf("com.google.firebase.crashlytics"), listOf("com.google.firebase:firebase-crashlytics")),
    SdkRule("Firebase Analytics", FIREBASE, "Mencatat peristiwa penggunaan aplikasi.", listOf("com.google.firebase.analytics"), listOf("com.google.firebase:firebase-analytics")),
    SdkRule("Firebase Auth", FIREBASE, "Login pengguna lewat Firebase.", listOf("com.google.firebase.auth"), listOf("com.google.firebase:firebase-auth")),
    SdkRule("Cloud Firestore", FIREBASE, "Database dokumen di cloud.", listOf("com.google.firebase.firestore"), listOf("com.google.firebase:firebase-firestore")),
    SdkRule("Firebase Realtime Database", FIREBASE, "Database JSON yang tersinkron real-time.", listOf("com.google.firebase.database"), listOf("com.google.firebase:firebase-database")),
    SdkRule("Firebase Remote Config", FIREBASE, "Mengubah konfigurasi aplikasi dari server.", listOf("com.google.firebase.remoteconfig"), listOf("com.google.firebase:firebase-config")),
    SdkRule("Google Maps", GOOGLE, "Menampilkan peta.", listOf("com.google.android.gms.maps"), listOf("com.google.android.gms:play-services-maps")),
    SdkRule("Google Location", GOOGLE, "Lokasi dari Fused Location Provider.", listOf("com.google.android.gms.location"), listOf("com.google.android.gms:play-services-location")),
    SdkRule("Google Sign-In / Credential Manager", GOOGLE, "Login dengan akun Google.", listOf("com.google.android.gms.auth.api.signin", "com.google.android.libraries.identity.googleid", "androidx.credentials"), listOf("com.google.android.gms:play-services-auth")),
    SdkRule("ML Kit", GOOGLE, "Model ML di perangkat (teks, barcode, wajah).", listOf("com.google.mlkit")),
    SdkRule("Play Billing", PAY, "Pembelian dalam aplikasi lewat Google Play.", listOf("com.android.billingclient")),
    SdkRule("Play Core / In-app update", GOOGLE, "Pembaruan dalam aplikasi, review, dan modul dinamis.", listOf("com.google.android.play.core")),
    SdkRule("Play Integrity", INTEGRITY, "Meminta penilaian Google Play tentang keaslian aplikasi dan perangkat.", listOf("com.google.android.play.core.integrity", "com.google.android.play.integrity")),
    SdkRule("SafetyNet", INTEGRITY, "Pemeriksaan integritas perangkat versi lama (digantikan Play Integrity).", listOf("com.google.android.gms.safetynet")),
    SdkRule("RootBeer", INTEGRITY, "Mendeteksi perangkat yang di-root.", listOf("com.scottyab.rootbeer")),
    SdkRule("Sentry", ANALYTICS, "Pemantauan error dan performa.", listOf("io.sentry")),
    SdkRule("Bugsnag", ANALYTICS, "Pelaporan crash.", listOf("com.bugsnag")),
    SdkRule("Amplitude", ANALYTICS, "Analitik produk.", listOf("com.amplitude")),
    SdkRule("Mixpanel", ANALYTICS, "Analitik produk.", listOf("com.mixpanel")),
    SdkRule("AppsFlyer", ANALYTICS, "Atribusi instalasi dan kampanye pemasaran.", listOf("com.appsflyer")),
    SdkRule("Adjust", ANALYTICS, "Atribusi instalasi dan kampanye pemasaran.", listOf("com.adjust.sdk")),
    SdkRule("OneSignal", ANALYTICS, "Layanan notifikasi push dan pesan.", listOf("com.onesignal")),
    SdkRule("Google AdMob", ADS, "Menampilkan iklan Google.", listOf("com.google.android.gms.ads"), listOf("com.google.android.gms:play-services-ads")),
    SdkRule("Meta Audience Network", ADS, "Menampilkan iklan dari jaringan Meta.", listOf("com.facebook.ads")),
    SdkRule("AppLovin", ADS, "Jaringan iklan dan mediasi.", listOf("com.applovin")),
    SdkRule("Unity Ads", ADS, "Iklan dalam game dari Unity.", listOf("com.unity3d.ads", "com.unity3d.services")),
    SdkRule("ironSource", ADS, "Jaringan iklan dan mediasi.", listOf("com.ironsource")),
    SdkRule("Pangle", ADS, "Jaringan iklan dari ByteDance.", listOf("com.bytedance.sdk.openadsdk")),
    SdkRule("Mintegral", ADS, "Jaringan iklan.", listOf("com.mbridge")),
    SdkRule("Vungle / Liftoff", ADS, "Iklan video.", listOf("com.vungle")),
    SdkRule("Facebook SDK", SOCIAL, "Login, berbagi, dan event aplikasi dari Meta.", listOf("com.facebook.login", "com.facebook.share", "com.facebook.appevents")),
    SdkRule("Stripe", PAY, "Pembayaran kartu dan dompet digital.", listOf("com.stripe.android")),
    SdkRule("Midtrans", PAY, "Gerbang pembayaran Indonesia.", listOf("com.midtrans")),
    SdkRule("Xendit", PAY, "Gerbang pembayaran Asia Tenggara.", listOf("com.xendit")),
)

private fun matches(pkg: String, prefix: String) = pkg == prefix || pkg.startsWith("$prefix.")

/**
 * Recognises well-known libraries from DEX package names. R8 can rename library packages, so
 * a missing library is not proof of absence, and a match shows inclusion, not active use.
 */
fun detectSdks(packages: Collection<String>, versions: Map<String, String>): List<DetectedSdk> = rules.mapNotNull { rule ->
    // Hilt lives under dagger.hilt; don't let it also count as plain Dagger evidence.
    val found = packages.filter { pkg -> rule.packages.any { matches(pkg, it) } &&
        !(rule.name == "Dagger" && matches(pkg, "dagger.hilt")) &&
        !(rule.name == "Play Core / In-app update" && matches(pkg, "com.google.android.play.core.integrity")) }
    if (found.isEmpty()) return@mapNotNull null
    DetectedSdk(rule.name, rule.category, rule.explanation, found.sorted().take(3), found.size,
        rule.artifacts.mapNotNull { a -> versions[a]?.let { "${a.substringAfter(':')} $it" } })
}

/** A hint (not proof) that R8/ProGuard renamed most classes, e.g. to a.b.c. */
fun obfuscationHint(code: CodeIndex): String? {
    if (code.definedClasses < 50) return null
    val share = code.shortNamedClasses * 100 / code.definedClasses
    return when {
        share >= 40 -> "$share% class bernama satu-dua huruf: kode kemungkinan besar diobfuskasi (R8/ProGuard). Nama yang terbaca sering hanya milik library yang dikecualikan."
        share >= 10 -> "$share% class bernama satu-dua huruf: sebagian kode tampak diobfuskasi."
        else -> null
    }
}
