package com.apkanatomy.domain

/** A term explained for learners; [tab] names the report tab where it shows up. */
data class GlossaryTerm(val term: String, val group: String, val explanation: String, val tab: String? = null, val aliases: List<String> = emptyList())

fun List<GlossaryTerm>.search(query: String): List<GlossaryTerm> {
    val q = query.trim()
    if (q.isEmpty()) return this
    // Matches in the term itself come before matches in the explanation.
    return filter { t -> t.term.contains(q, true) || t.aliases.any { it.contains(q, true) } } +
        filter { t -> !t.term.contains(q, true) && t.aliases.none { it.contains(q, true) } && t.explanation.contains(q, true) }
}

private const val PACKAGE = "Paket & build"
private const val COMPONENTS = "Komponen & manifest"
private const val CODE = "Kode"
private const val SECURITY = "Keamanan & tanda tangan"
private const val NETWORK = "Jaringan"
private const val NATIVE = "Native"
private const val PRIVACY = "Privasi"

val GLOSSARY: List<GlossaryTerm> = listOf(
    GlossaryTerm("APK", PACKAGE, "Android Package: arsip ZIP berisi kode (DEX), resource, manifest, library native, dan tanda tangan. Inilah yang dipasang di perangkat.", "Isi APK"),
    GlossaryTerm("Split APK", PACKAGE, "Aplikasi dari Google Play sering dipecah menjadi base.apk plus split per ABI, bahasa, atau kepadatan layar. Semua split harus ditandatangani dengan kunci yang sama.", "Isi APK",
        listOf("split", "base.apk")),
    GlossaryTerm("App Bundle (.aab)", PACKAGE, "Format unggahan ke Google Play. Play membuat split APK yang sesuai untuk tiap perangkat; file .apks/.xapk/.apkm adalah kumpulan split hasil ekspor.", null, listOf("aab", "apks", "xapk", "apkm")),
    GlossaryTerm("resources.arsc", PACKAGE, "Tabel resource terkompilasi: memetakan ID 0x7f… ke nama (string/app_name) dan nilainya per konfigurasi seperti bahasa atau ukuran layar.", "Teknologi",
        listOf("resource", "arsc")),
    GlossaryTerm("Binary XML (AXML)", PACKAGE, "Manifest dan layout di dalam APK disimpan dalam format XML biner yang ringkas; APKAnatomy mendekodenya kembali menjadi teks.", "Manifest", listOf("axml")),
    GlossaryTerm("minSdk / targetSdk", PACKAGE, "minSdk adalah versi Android terendah yang didukung. targetSdk menyatakan versi yang perilakunya sudah diuji pengembang; banyak aturan keamanan baru hanya berlaku bila targetSdk cukup tinggi.", "Ringkasan",
        listOf("minsdk", "targetsdk", "api level")),
    GlossaryTerm("R8 / ProGuard", PACKAGE, "Alat build yang mengecilkan dan menyamarkan kode: nama class menjadi a, b, c, dan kode yang tidak dipakai dibuang. Membuat analisis lebih sulit, tetapi tidak mengenkripsi kode.", "Teknologi",
        listOf("obfuscation", "obfuskasi", "proguard", "minify")),
    GlossaryTerm("SDK pihak ketiga", PACKAGE, "Library dari luar (iklan, analitik, pembayaran) yang ikut dikemas di APK. Dikenali dari nama package di DEX dan file versi di META-INF.", "Teknologi",
        listOf("library", "sdk")),

    GlossaryTerm("AndroidManifest.xml", COMPONENTS, "Deklarasi identitas aplikasi: package, izin, komponen, intent-filter, dan konfigurasi keamanan. Sistem membacanya sebelum menjalankan kode apa pun.", "Manifest",
        listOf("manifest")),
    GlossaryTerm("Activity", COMPONENTS, "Satu layar yang bisa dibuka pengguna atau aplikasi lain. Activity dengan intent-filter MAIN/LAUNCHER muncul di peluncur.", "Komponen"),
    GlossaryTerm("Service", COMPONENTS, "Komponen untuk kerja tanpa layar, misalnya memutar musik atau sinkronisasi. Foreground service wajib menampilkan notifikasi.", "Komponen"),
    GlossaryTerm("Broadcast receiver", COMPONENTS, "Menerima pengumuman sistem atau aplikasi, misalnya BOOT_COMPLETED setelah perangkat menyala.", "Komponen", listOf("receiver", "broadcast")),
    GlossaryTerm("Content provider", COMPONENTS, "Gerbang data berbasis URI content://. Aplikasi lain dapat membaca atau menulis lewat provider bila diizinkan.", "Komponen", listOf("provider")),
    GlossaryTerm("Exported", COMPONENTS, "Komponen exported dapat dipanggil aplikasi lain. Tanpa izin pelindung, aplikasi apa pun bisa memanggilnya, sehingga input darinya harus diperiksa.", "Komponen",
        listOf("android:exported")),
    GlossaryTerm("Intent & intent-filter", COMPONENTS, "Intent adalah pesan \"lakukan sesuatu\". Intent-filter menyatakan intent mana yang diterima sebuah komponen: action, category, dan data seperti skema URL.", "Komponen",
        listOf("intent", "intent-filter", "action")),
    GlossaryTerm("Deep link / App Link", COMPONENTS, "URL yang membuka layar tertentu di aplikasi. App Link (autoVerify) dibuktikan lewat file assetlinks.json di domain, sehingga aplikasi lain tidak bisa merebutnya.", "Komponen",
        listOf("deep link", "app link", "autoverify")),
    GlossaryTerm("Izin (permission)", COMPONENTS, "Hak akses yang diminta aplikasi. Izin normal diberikan otomatis; izin dangerous perlu persetujuan pengguna saat berjalan; izin signature hanya untuk aplikasi dengan tanda tangan yang sama.", "Izin",
        listOf("permission", "dangerous", "runtime permission")),
    GlossaryTerm("Package visibility (<queries>)", COMPONENTS, "Sejak Android 11 aplikasi hanya melihat aplikasi lain yang dinyatakan di <queries> atau lewat izin QUERY_ALL_PACKAGES.", "Kemampuan",
        listOf("queries", "query_all_packages")),
    GlossaryTerm("Task affinity / StrandHogg", COMPONENTS, "taskAffinity menentukan tumpukan layar tempat activity bergabung. Bila diarahkan ke aplikasi lain bersama allowTaskReparenting, layar palsu bisa menyusup ke tumpukan aplikasi korban (pola StrandHogg).", "Kemampuan",
        listOf("taskaffinity", "strandhogg", "task hijacking")),

    GlossaryTerm("DEX", CODE, "Dalvik Executable: bytecode Android hasil kompilasi Java/Kotlin. Satu DEX hanya dapat mereferensikan 65.536 method, sehingga aplikasi besar punya classes2.dex, classes3.dex, dan seterusnya.", "Kode",
        listOf("classes.dex", "dalvik", "multidex")),
    GlossaryTerm("Smali / instruksi DEX", CODE, "Bentuk teks dari instruksi DEX, misalnya invoke-virtual atau const-string. Tab Kode menampilkan ringkasannya, bukan kode Java asli.", "Kode",
        listOf("smali", "bytecode", "disassembly")),
    GlossaryTerm("Descriptor", CODE, "Penulisan nama di DEX: class Lcom/contoh/Api; dan method Lcom/contoh/Api;->login(Ljava/lang/String;)V (parameter String, kembalian void).", "Kode",
        listOf("signature", "type descriptor")),
    GlossaryTerm("const-string", CODE, "Instruksi yang memuat string literal ke register. Dengan ini APKAnatomy menautkan URL atau kunci ke method yang memakainya.", "Jejak"),
    GlossaryTerm("Find usage / xref", CODE, "Mencari tempat sebuah method dipanggil (cross-reference). Hanya pemanggilan langsung yang terlihat; reflection dan pemuatan dinamis tidak.", "Kode",
        listOf("xref", "cross-reference", "find usage")),
    GlossaryTerm("Reflection", CODE, "Memanggil class atau method berdasarkan nama string saat berjalan (Class.forName, Method.invoke). Umum di library, tetapi menyembunyikan jalur pemanggilan dari analisis statis.", "Perilaku"),
    GlossaryTerm("Pemuatan kode dinamis", CODE, "DexClassLoader dan sejenisnya memuat kode yang tidak ada di APK saat diperiksa, misalnya plugin atau hasil unduhan. Kode itu tidak terlihat oleh analisis statis.", "Perilaku",
        listOf("dexclassloader", "dynamic code loading")),
    GlossaryTerm("Analisis statis", CODE, "Memeriksa file tanpa menjalankannya, seperti yang dilakukan APKAnatomy. Menunjukkan kode yang ada, bukan bukti kode itu dijalankan. Lawannya analisis dinamis (mengamati aplikasi saat berjalan).", null,
        listOf("static analysis", "analisis dinamis")),

    GlossaryTerm("Tanda tangan APK (v1–v4)", SECURITY, "Setiap APK ditandatangani pengembang. v1 (JAR) menandatangani per file; v2/v3 menandatangani seluruh arsip di APK Signing Block; v3 mendukung rotasi kunci; v4 untuk instalasi bertahap.", "DNA",
        listOf("signature scheme", "apk signing block", "v2", "v3")),
    GlossaryTerm("Sertifikat penandatangan", SECURITY, "Identitas kunci pengembang. Pembaruan aplikasi hanya diterima bila ditandatangani kunci yang sama; sidik jari SHA-256 dipakai untuk memeriksanya.", "DNA",
        listOf("certificate", "sha-256", "fingerprint")),
    GlossaryTerm("debuggable", SECURITY, "Flag manifest yang mengizinkan debugger menempel dan membaca data aplikasi. Seharusnya mati pada rilis.", "DNA"),
    GlossaryTerm("allowBackup", SECURITY, "Mengizinkan data aplikasi ikut backup (adb atau cloud). Data sensitif sebaiknya dikecualikan lewat aturan backup.", "DNA", listOf("backup")),
    GlossaryTerm("Android Keystore", SECURITY, "Tempat penyimpanan kunci kriptografi yang dijaga sistem (bisa didukung hardware); kunci dipakai tanpa pernah keluar dalam bentuk mentah.", "Perilaku", listOf("keystore")),
    GlossaryTerm("Mode ECB", SECURITY, "Mode enkripsi blok yang mengenkripsi setiap blok secara terpisah, sehingga pola data tetap terlihat. AES/GCM adalah pilihan yang aman.", "Perilaku", listOf("ecb", "aes")),
    GlossaryTerm("Deteksi root", SECURITY, "Pemeriksaan apakah perangkat di-root (misalnya mencari file su atau Magisk). Umum di aplikasi bank dan game.", "Perilaku", listOf("root", "magisk")),

    GlossaryTerm("HTTPS / TLS", NETWORK, "Koneksi terenkripsi antara aplikasi dan server. Keamanannya bergantung pada pemeriksaan sertifikat server.", "Perilaku", listOf("tls", "ssl", "https")),
    GlossaryTerm("Cleartext traffic", NETWORK, "Lalu lintas HTTP tanpa enkripsi; bisa dibaca atau diubah di jaringan. Sejak targetSdk 28 diblokir kecuali diizinkan.", "DNA", listOf("http", "cleartext")),
    GlossaryTerm("Network Security Config", NETWORK, "File XML yang mengatur kepercayaan jaringan: domain yang boleh HTTP, CA yang dipercaya, dan certificate pinning.", "DNA", listOf("nsc", "network_security_config")),
    GlossaryTerm("Certificate pinning", NETWORK, "Aplikasi hanya menerima sertifikat atau kunci server tertentu, sehingga penyadapan dengan CA palsu gagal.", "Perilaku", listOf("pinning", "pin-set")),
    GlossaryTerm("Man-in-the-middle (MITM)", NETWORK, "Penyerang di tengah jaringan yang membaca atau mengubah lalu lintas. TrustManager yang menerima semua sertifikat membuat serangan ini mudah.", "Perilaku",
        listOf("mitm", "trustmanager")),
    GlossaryTerm("Endpoint", NETWORK, "Alamat server yang dihubungi aplikasi, misalnya https://api.contoh.id/v2. Tab Jejak mengumpulkannya dari string di APK.", "Jejak", listOf("host", "url", "api")),

    GlossaryTerm("Library native (.so)", NATIVE, "Kode mesin hasil kompilasi C/C++/Rust untuk ABI tertentu, dimuat lewat System.loadLibrary dan dipanggil lewat JNI.", "Teknologi",
        listOf("so", "ndk", "native library")),
    GlossaryTerm("ABI", NATIVE, "Arsitektur prosesor target: arm64-v8a (umum di HP modern), armeabi-v7a (32-bit lama), x86_64 (emulator dan sebagian Chromebook).", "Teknologi",
        listOf("arm64-v8a", "armeabi-v7a", "x86_64")),
    GlossaryTerm("JNI", NATIVE, "Java Native Interface: jembatan antara kode Java/Kotlin dan kode native. Fungsi ekspor Java_com_contoh_… dipanggil dari method native.", "Teknologi", listOf("jni_onload")),
    GlossaryTerm("16 KB page size", NATIVE, "Perangkat Android baru dapat memakai halaman memori 16 KB. Library 64-bit harus disejajarkan (LOAD align ≥ 16 KB), dan Google Play mewajibkannya untuk target Android 15 ke atas.", "Teknologi",
        listOf("page size", "16kb", "alignment")),

    GlossaryTerm("Pelacak (tracker)", PRIVACY, "SDK yang mengumpulkan data penggunaan untuk iklan, analitik, atau atribusi pemasaran. Keberadaannya bukan bukti data terkirim, tetapi menunjukkan kemampuan itu ada.", "Privasi",
        listOf("tracker", "analytics", "analitik")),
    GlossaryTerm("Advertising ID", PRIVACY, "ID yang bisa direset pengguna, dipakai untuk penargetan iklan lintas aplikasi.", "Perilaku", listOf("gaid", "ad id")),
    GlossaryTerm("ANDROID_ID", PRIVACY, "Pengenal unik per kunci penandatangan aplikasi, pengguna, dan perangkat (sejak Android 8); sering dipakai untuk mengenali perangkat tanpa izin khusus.", "Perilaku", listOf("android_id")),
    GlossaryTerm("Data safety", PRIVACY, "Bagian di Google Play tempat pengembang menyatakan data yang dikumpulkan dan dibagikan. Bandingkan dengan tab Privasi dan Perilaku.", "Privasi", listOf("keamanan data")),
)
