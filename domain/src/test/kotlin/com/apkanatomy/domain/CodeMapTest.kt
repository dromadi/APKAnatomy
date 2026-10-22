package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class CodeMapTest {
    @Test fun groupsPackagesByOwner() {
        val report = AnatomyReport("org.toko.app", "Toko", "1", 26, 35, "-",
            components = listOf(AppComponent("org.toko.ui.Main", ComponentKind.ACTIVITY, true, true, null)), permissions = emptyList(),
            code = CodeIndex(packageClasses = mapOf("org.toko.ui" to 40, "org.toko.data" to 10, "androidx.core.view" to 30, "androidx.compose.ui" to 70,
                "okhttp3" to 20, "okhttp3.internal" to 15, "com.google.firebase.messaging" to 25, "a" to 5)),
            sdks = listOf(DetectedSdk("OkHttp", "Jaringan", "", listOf("okhttp3"), 2)))
        val map = report.codeMap()
        assertEquals(listOf("androidx.compose" to 70, "org.toko" to 50, "okhttp3" to 35, "androidx.core" to 30, "com.google.firebase" to 25, "kode tersamar (a, b, …)" to 5),
            map.map { it.name to it.classes })
        assertEquals(CodeOwner.APP, map.single { it.name == "org.toko" }.owner)
        assertEquals(CodeOwner.PLATFORM, map.single { it.name == "com.google.firebase" }.owner)
        assertEquals("OkHttp" to CodeOwner.LIBRARY, map.single { it.name == "okhttp3" }.let { it.sdk to it.owner })
        assertEquals(CodeOwner.OBFUSCATED, map.last().owner)
        assertEquals(listOf("androidx.compose", "org.toko", "lainnya (4 grup)"), report.codeMap(maxBlocks = 3).map { it.name })
    }

    @Test fun treemapFillsTheAreaProportionally() {
        val values = listOf(6.0, 6.0, 4.0, 3.0, 2.0, 2.0, 1.0)
        val rects = squarify(values, 6f, 4f)
        assertEquals(values.size, rects.size)
        rects.zip(values).forEach { (r, v) -> assertEquals(v, (r.w * r.h).toDouble(), 1e-3) }
        assertTrue(rects.all { it.x >= -1e-4 && it.y >= -1e-4 && it.x + it.w <= 6.0001f && it.y + it.h <= 4.0001f })
        // The first two rectangles stack in the first column, as in the paper's example.
        assertEquals(MapRect(0f, 0f, 3f, 2f), rects[0])
    }
}
