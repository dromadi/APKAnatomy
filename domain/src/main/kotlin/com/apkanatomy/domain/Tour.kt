package com.apkanatomy.domain

/**
 * One step of the guided tour: what this report shows ([observation]), a question to think about, and the
 * answer. [tab] is where to look.
 */
data class TourStep(val title: String, val tab: String, val observation: String, val question: String, val answer: String)

/** A guided reading of this report, from who made the app to what its code does, written from its own findings. */
fun AnatomyReport.tour(): List<TourStep> = buildList {
    val cert = signing.certificates.firstOrNull()
    add(TourStep("Siapa pembuatnya?", "DNA",
        if (cert == null) "APK ini tidak memiliki sertifikat penandatangan yang terbaca."
        else "Ditandatangani oleh ${cert.subject} dengan skema ${signing.schemes.joinToString { it.label }.ifEmpty { "-" }}." +
            if (signing.keyRotation) " Kuncinya pernah dirotasi." else "",
        "Mengapa Android menolak pembaruan yang ditandatangani kunci lain?",
        "Tanda tangan membuktikan pembaruan datang dari pemegang kunci yang sama. Tanpa itu, siapa pun bisa membuat APK berpackage sama dan mengambil alih data aplikasi."))

    val launcher = components.firstOrNull { c -> c.intentFilters.any { "android.intent.category.LAUNCHER" in it.categories } }
    val open = components.openToAllApps()
    add(TourStep("Pintu masuk aplikasi", "Komponen",
        "${components.size} komponen: ${ComponentKind.entries.joinToString { k -> "${components.count { it.kind == k }} ${k.name.lowercase()}" }}. " +
            (launcher?.let { "Layar pertama: ${it.name.substringAfterLast('.')}. " } ?: "") + "${open.size} dapat dipanggil aplikasi lain tanpa izin.",
        "Mengapa komponen exported tanpa izin perlu diperiksa?",
        "Aplikasi lain dapat memanggilnya dengan data buatan sendiri. Activity peluncur memang harus terbuka, tetapi service, receiver, atau provider yang terbuka harus memvalidasi semua input."))

    val privacy = privacyProfile()
    add(TourStep("Apa yang bisa diketahui tentang Anda?", "Privasi", privacy.summary,
        "Apakah izin di manifest berarti data pasti diambil?",
        "Tidak. Izin hanya kemampuan yang diminta; izin runtime bisa ditolak pengguna, dan SDK yang dibundel belum tentu dipakai. Tab Izin menunjukkan apakah API-nya terlihat dipanggil di kode."))

    val ui = uiStack()
    val map = codeMap()
    val appShare = map.filter { it.owner == CodeOwner.APP }.sumOf { it.classes } * 100 / code.definedClasses.coerceAtLeast(1)
    add(TourStep("Dari apa aplikasi ini dibangun?", "Teknologi",
        "Antarmuka: ${ui.primary ?: "tidak dikenali"}. ${sdks.size} SDK dikenali" + (sdks.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")") { it.name }?.take(120) ?: "") +
            ". Sekitar $appShare% class adalah kode aplikasi sendiri.",
        "Mengapa sebagian besar kode APK sering bukan kode pembuatnya?",
        "Aplikasi modern dibangun di atas library: AndroidX, Kotlin, jaringan, analitik, iklan. R8 membuang yang tidak dipakai, tetapi sisanya tetap jauh lebih besar dari kode aplikasi itu sendiri."))

    val top = behaviors.findings.firstOrNull()
    add(TourStep("Apa yang dilakukan kodenya?", "Perilaku",
        if (top == null) "Tidak ada pemanggilan API sensitif yang dikenali." else "${behaviors.findings.size} perilaku ditemukan. Yang paling atas: ${top.title} (${top.count} tempat, ${top.inAppPackage} di kode aplikasi sendiri).",
        "Mengapa temuan di library berbeda bobotnya dengan temuan di kode aplikasi?",
        "Library sering membawa fitur yang tidak pernah dipanggil aplikasi. Temuan di package aplikasi sendiri lebih mungkin benar-benar dijalankan; tab Jalur di Kode membantu melihat apakah ada jalur dari komponen ke sana."))

    add(TourStep("Ke mana aplikasi terhubung?", "Jejak",
        "${traces.hosts.size} host ditemukan" + (traces.hosts.take(3).takeIf { it.isNotEmpty() }?.joinToString(prefix = ", mis. ") { it.host } ?: "") +
            ". ${traces.secrets.size} string mirip kunci atau token.",
        "Apakah kunci API di dalam APK selalu masalah?",
        "Tidak selalu. Kunci klien (mis. Google Maps) memang disematkan dan seharusnya dibatasi di konsol penyedianya. Kunci server atau private key tidak boleh ada di APK, karena siapa pun bisa mengekstraknya."))

    val notes = notes(System.currentTimeMillis())
    add(TourStep("Seberapa rapi keamanannya?", "DNA",
        "${notes.count { it.level == NoteLevel.PERHATIAN }} catatan perlu perhatian, ${notes.count { it.level == NoteLevel.BAIK }} catatan baik. " +
            (notes.firstOrNull { it.level == NoteLevel.PERHATIAN }?.let { "Contoh: ${it.title}." } ?: ""),
        "Apa beda analisis statis seperti ini dengan pengujian keamanan sesungguhnya?",
        "Analisis statis membaca isi APK tanpa menjalankannya: cepat dan aman, tetapi tidak melihat kode yang dimuat saat berjalan atau perilaku server. Pengujian lengkap juga menjalankan aplikasi dan mengamati lalu lintasnya."))

    val launcherClass = launcher?.let { l -> code.classes.firstOrNull { it.displayName == l.name } }
    add(TourStep("Baca kodenya sendiri", "Kode",
        if (launcherClass != null) "Buka class ${launcherClass.displayName} di tab Kode, lalu pilih onCreate. Kode semu menunjukkan apa yang dilakukan saat layar pertama dibuka."
        else "Cari nama class atau string di tab Kode, lalu buka method-nya untuk melihat kode semu dan instruksi DEX.",
        "Mengapa nama variabel di kode semu hanya v0, v1, p0?",
        "Nama variabel lokal tidak disimpan di DEX rilis; yang tersisa hanya register. Nama class dan method juga sering disamarkan R8, sehingga membaca kode berarti menyimpulkan dari panggilan API dan string."))
}
