package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class ProtectionTest {
    private fun report(paths: List<String>, packages: List<String> = emptyList(), classes: Int = 500, short: Int = 10, behaviors: BehaviorReport = BehaviorReport()) =
        AnatomyReport("x", "X", "1", 26, 35, "-", emptyList(), emptyList(),
            structure = PackageStructure(entries = paths.map { ArchiveEntry("base.apk", it, 10, 10) },
                libraries = paths.filter { it.endsWith(".so") }.map { NativeLibrary("base.apk", it, it.split('/')[1], 10) },
                dex = listOf(DexMetrics("base.apk", "classes.dex", "035", 10, classes.toLong(), null))),
            code = CodeIndex(packages = packages, definedClasses = classes, shortNamedClasses = short), behaviors = behaviors)

    @Test fun recognisesPackersByLibraryOrPackage() {
        val jiagu = report(listOf("lib/arm64-v8a/libjiagu_a64.so", "classes.dex"), classes = 12)
        val names = jiagu.protections().map { it.name }
        assertEquals(listOf("Qihoo 360 Jiagu", "DEX utama sangat kecil"), names)
        assertEquals(listOf("Dilindungi: Qihoo 360 Jiagu", "Dilindungi: DEX utama sangat kecil"), jiagu.notes(0).filter { it.title.startsWith("Dilindungi") }.map { it.title })
        assertEquals("Bangcle / SecNeo", report(emptyList(), packages = listOf("com.secneo.apkwrapper")).protections().single().name)
        // A package that merely starts with the same letters is not evidence.
        assertTrue(report(emptyList(), packages = listOf("com.stubborn")).protections().isEmpty())
    }

    @Test fun findsHiddenCodeObfuscationAndRuntimeChecks() {
        val c = BehaviorCollector().apply { method("base.apk:classes.dex:La/B;->c()V", listOf("Landroid/os/Debug;" to "isDebuggerConnected"), emptyList(), 1) }.result()
        val kinds = report(listOf("assets/plugin.jar", "classes.dex"), short = 300, behaviors = c).protections().associate { it.name to it.kind }
        assertEquals(mapOf("Kode tambahan di assets" to "Kode tersembunyi", "Nama class disamarkan (R8/ProGuard)" to "Penyamaran",
            "Pemeriksaan lingkungan & integritas" to "Pemeriksaan saat berjalan"), kinds)
        assertTrue(report(listOf("classes.dex", "assets/fonts/a.ttf")).protections().isEmpty())
    }
}
