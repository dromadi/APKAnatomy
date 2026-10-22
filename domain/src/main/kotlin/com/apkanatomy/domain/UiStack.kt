package com.apkanatomy.domain

/** One way the app draws its screens, with the evidence for it. */
data class UiSignal(val name: String, val explanation: String, val evidence: List<String>)

/** [primary] is the most likely way the main screens are built; [signals] lists every toolkit found. */
data class UiStack(val primary: String?, val signals: List<UiSignal>)

/**
 * How the user interface is built, from file names and DEX packages. Order matters: a cross-platform engine
 * (Flutter, React Native, a game engine) draws the screens even when AndroidX Views or Compose are bundled too.
 */
fun AnatomyReport.uiStack(): UiStack {
    val paths = structure.entries.map { it.path }
    val names = paths.map { it.substringAfterLast('/') }
    fun files(predicate: (String) -> Boolean) = paths.filter(predicate)
    fun lib(vararg so: String) = paths.filter { p -> p.startsWith("lib/") && so.any { p.endsWith("/$it") } }
    fun classes(prefix: String) = code.packageClasses.entries.filter { it.key == prefix || it.key.startsWith("$prefix.") }.sumOf { it.value }
    val signals = mutableListOf<UiSignal>()
    fun add(name: String, explanation: String, evidence: List<String>) { if (evidence.isNotEmpty()) signals += UiSignal(name, explanation, evidence.distinct().take(4)) }

    add("Flutter", "Layar digambar oleh mesin Flutter dari kode Dart (libapp.so berisi kode Dart yang sudah dikompilasi); widget Android biasa hanya menjadi wadah.",
        lib("libflutter.so", "libapp.so") + files { it.startsWith("assets/flutter_assets/") }.take(1))
    add("React Native", "Antarmuka ditulis dalam JavaScript/TypeScript (bundle di assets) dan diterjemahkan ke View Android asli saat berjalan.",
        lib("libreactnativejni.so", "libreactnative.so", "libhermes.so") + files { it == "assets/index.android.bundle" } + if (classes("com.facebook.react") > 0) listOf("package com.facebook.react") else emptyList())
    add("Unity", "Game atau tampilan 3D digambar oleh mesin Unity; " + if (lib("libil2cpp.so").isNotEmpty()) "kode C# dikompilasi ke native (IL2CPP)." else "kode C# berjalan di Mono.",
        lib("libunity.so", "libil2cpp.so", "libmain.so").filter { lib("libunity.so").isNotEmpty() } + files { it.startsWith("assets/bin/Data/") }.take(1))
    add("Unreal Engine", "Game digambar oleh Unreal Engine (C++).", lib("libUE4.so", "libUnreal.so"))
    add("Cocos", "Game dibuat dengan Cocos2d-x/Cocos Creator.", paths.filter { it.startsWith("lib/") && names[paths.indexOf(it)].startsWith("libcocos") })
    add("Godot", "Game dibuat dengan mesin Godot.", lib("libgodot_android.so"))
    add(".NET MAUI / Xamarin", "Antarmuka ditulis dalam C# (.NET); assembly .NET dibawa di dalam APK.",
        lib("libmonodroid.so", "libmonosgen-2.0.so") + files { it.startsWith("assemblies/") || (it.startsWith("lib/") && "libassemblies." in it) }.take(1))
    add("Web (Cordova / Capacitor / Ionic)", "Antarmuka adalah halaman web (HTML/CSS/JS di assets) yang ditampilkan di WebView, dengan plugin untuk akses perangkat.",
        files { it == "assets/www/cordova.js" || it == "assets/www/index.html" || it == "assets/public/index.html" || it.endsWith("capacitor.config.json") } +
            if (classes("org.apache.cordova") > 0) listOf("package org.apache.cordova") else emptyList())
    add("Compose Multiplatform", "Antarmuka Compose dari JetBrains yang dapat dipakai bersama di Android, desktop, dan iOS (Kotlin Multiplatform).",
        if (classes("org.jetbrains.compose") > 0) listOf("package org.jetbrains.compose (${classes("org.jetbrains.compose")} class)") else emptyList())
    val compose = classes("androidx.compose.ui")
    add("Jetpack Compose", "Layar ditulis sebagai fungsi Kotlin @Composable, tanpa file layout XML.",
        if (compose > 0) listOf("package androidx.compose.ui ($compose class)") else emptyList())
    val layouts = files { it.startsWith("res/layout") && it.endsWith(".xml") }
    add("View & layout XML", "Cara klasik: layar disusun dari file layout XML di res/layout dan class View/Fragment. Sebagian layout bisa berasal dari library.",
        if (layouts.isNotEmpty()) listOf("${layouts.size} file layout") + layouts.take(3) else emptyList())
    if (components.any { it.name == "android.app.NativeActivity" })
        add("NativeActivity", "Activity bawaan Android yang menyerahkan seluruh tampilan ke kode C/C++ (biasanya game).", listOf("android.app.NativeActivity di manifest"))

    val engines = listOf("Flutter", "React Native", "Unity", "Unreal Engine", "Cocos", "Godot", ".NET MAUI / Xamarin", "Web (Cordova / Capacitor / Ionic)", "Compose Multiplatform")
    val primary = signals.firstOrNull { it.name in engines }?.name
        ?: if (compose >= 200 && layouts.size < 40) "Jetpack Compose" else signals.firstOrNull { it.name == "View & layout XML" }?.name ?: signals.firstOrNull()?.name
    return UiStack(primary, signals)
}
