package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class UiStackTest {
    private fun report(paths: List<String>, packages: Map<String, Int> = emptyMap()) = AnatomyReport("x", "X", "1", 26, 35, "-", emptyList(), emptyList(),
        structure = PackageStructure(entries = paths.map { ArchiveEntry("base.apk", it, 1, 1) }), code = CodeIndex(packageClasses = packages))

    @Test fun engineWinsOverBundledAndroidToolkits() {
        val flutter = report(listOf("lib/arm64-v8a/libflutter.so", "lib/arm64-v8a/libapp.so", "assets/flutter_assets/AssetManifest.json", "res/layout/a.xml"),
            mapOf("androidx.compose.ui.node" to 500))
        val stack = flutter.uiStack()
        assertEquals("Flutter", stack.primary)
        assertEquals(listOf("Flutter", "Jetpack Compose", "View & layout XML"), stack.signals.map { it.name })
    }

    @Test fun composeVersusViews() {
        assertEquals("Jetpack Compose", report(listOf("res/layout/x.xml"), mapOf("androidx.compose.ui" to 800)).uiStack().primary)
        assertEquals("View & layout XML", report(List(60) { "res/layout/l$it.xml" }, mapOf("androidx.compose.ui" to 800)).uiStack().primary)
        assertEquals("View & layout XML", report(listOf("res/layout-land/main.xml")).uiStack().primary)
        assertNull(report(listOf("classes.dex")).uiStack().primary)
    }

    @Test fun recognisesWebAndGameEngines() {
        assertEquals("Web (Cordova / Capacitor / Ionic)", report(listOf("assets/www/cordova.js", "assets/www/index.html")).uiStack().primary)
        assertEquals("Unity", report(listOf("lib/arm64-v8a/libunity.so", "lib/arm64-v8a/libil2cpp.so")).uiStack().primary)
        assertTrue(report(listOf("lib/arm64-v8a/libunity.so", "lib/arm64-v8a/libil2cpp.so")).uiStack().signals.single().explanation.contains("IL2CPP"))
        assertEquals("React Native", report(listOf("assets/index.android.bundle")).uiStack().primary)
    }
}
