package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class SkinAndExportTest {
    private fun entry(path: String, size: Long, compressed: Long = size / 2) = ArchiveEntry("base.apk", path, size, compressed)
    private val entries = listOf(entry("classes.dex", 600), entry("classes2.dex", 400), entry("lib/arm64-v8a/libx.so", 300),
        entry("res/font/inter.ttf", 50), entry("assets/model.tflite", 200), entry("assets/www/index.html", 10),
        entry("resources.arsc", 80), entry("META-INF/CERT.SF", 5), entry("unknown.bin", -1), entry("assets/certs/pin.pem", 2))

    @Test fun breaksDownSizeLargestFirst() {
        val slices = sizeBreakdown(entries)
        assertEquals(listOf(SizeCategory.DEX, SizeCategory.NATIVE, SizeCategory.ASSETS, SizeCategory.ARSC, SizeCategory.RES, SizeCategory.META), slices.map { it.category })
        assertEquals(SizeSlice(SizeCategory.DEX, 2, 1000, 500), slices[0])
    }

    @Test fun groupsNotableFiles() {
        val groups = notableFiles(entries).associate { it.title to it.files.map { f -> f.path } }
        assertEquals(listOf("res/font/inter.ttf"), groups["Font"])
        assertEquals(listOf("assets/model.tflite"), groups["Model machine learning"])
        assertEquals(listOf("assets/www/index.html"), groups["Web bundle"])
        assertEquals(listOf("assets/certs/pin.pem"), groups["Sertifikat & keystore"])
        assertNull(groups["Media"])
    }

    private val report = AnatomyReport("com.example", "Contoh \"App\"\n", "1.0", 26, 35, "File APK lokal",
        listOf(AppComponent("com.example.Main", ComponentKind.ACTIVITY, true, true, null,
            intentFilters = listOf(IntentFilterInfo(listOf("android.intent.action.MAIN"), listOf("android.intent.category.LAUNCHER"))))),
        listOf(AppPermission("android.permission.CAMERA", "Dangerous"), AppPermission("android.permission.INTERNET", "Normal")),
        PackageStructure(entries = entries), signing = SigningReport(setOf(SignatureScheme.V2)))

    @Test fun markdownSummarisesEveryPillar() {
        val markdown = report.toMarkdown(0)
        listOf("# Laporan APKAnatomy", "## DNA & keamanan", "Skema tanda tangan: v2", "## Kerangka", "| `com.example.Main` | activity | ya, terbuka |",
            "MAIN LAUNCHER", "`android.permission.CAMERA` (Dangerous) ⚠", "## Organ", "## Kulit: ukuran", "| Kode DEX | 2 | 1000 B | 500 B |")
            .forEach { assertTrue(it, markdown.contains(it)) }
        assertTrue(markdown.indexOf("CAMERA") < markdown.indexOf("INTERNET"))
    }

    @Test fun jsonEscapesStrings() {
        val json = report.toJson(1)
        assertTrue(json.startsWith("{\"tool\":\"APKAnatomy\",\"generatedAt\":1,"))
        assertTrue(json.contains("\"label\":\"Contoh \\\"App\\\"\\n\""))
        assertTrue(json.contains("\"security\":null"))
        assertTrue(json.contains("\"sensitive\":true"))
    }
}
