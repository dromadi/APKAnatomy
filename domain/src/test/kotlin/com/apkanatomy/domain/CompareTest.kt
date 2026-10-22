package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class CompareTest {
    private fun cert(sha: String) = SignerCertificate("CN=$sha", "CN=$sha", "1", 0, Long.MAX_VALUE, "SHA256withRSA", "RSA", 2048, sha, "x", "y", listOf("v2"))
    private val base = AnatomyReport("com.example", "Contoh", "1.0", 26, 34, "Aplikasi terinstal",
        components = listOf(AppComponent("com.example.Main", ComponentKind.ACTIVITY, true, true, null),
            AppComponent("com.example.Sync", ComponentKind.SERVICE, false, true, null)),
        permissions = listOf(AppPermission("android.permission.INTERNET", "Normal")),
        structure = PackageStructure(entries = listOf(ArchiveEntry("base.apk", "classes.dex", 10L * 1024 * 1024, 1)), libraryVersions = mapOf("androidx.core:core" to "1.0")),
        signing = SigningReport(setOf(SignatureScheme.V2), listOf(cert("aa"))),
        sdks = listOf(DetectedSdk("OkHttp", "Jaringan", "", listOf("okhttp3"), 1)))

    @Test fun identicalReportsHaveNoDifferences() {
        assertTrue(compareReports(base, base.copy(), 0).isEmpty())
    }

    @Test fun reportsMeaningfulChangesAndFlagsRiskyOnes() {
        val update = base.copy(version = "2.0", targetSdk = 35,
            components = listOf(AppComponent("com.example.Main", ComponentKind.ACTIVITY, true, true, null),
                AppComponent("com.example.Sync", ComponentKind.SERVICE, true, true, null)),
            permissions = base.permissions + AppPermission("android.permission.READ_SMS", "Dangerous"),
            structure = base.structure.copy(entries = listOf(ArchiveEntry("base.apk", "classes.dex", 20L * 1024 * 1024, 1)), libraryVersions = mapOf("androidx.core:core" to "1.1")),
            signing = SigningReport(setOf(SignatureScheme.V2, SignatureScheme.V3), listOf(cert("bb"))),
            sdks = base.sdks + DetectedSdk("Google AdMob", "Iklan", "", listOf("com.google.android.gms.ads"), 1))
        val diff = compareReports(base, update, 0).associate { it.title to it.changes }
        assertEquals(listOf("Versi: 1.0 → 2.0", "targetSdk: 34 → 35"), diff.getValue("Identitas").map { it.text })
        assertTrue(diff.getValue("DNA").first().let { it.attention && it.text.startsWith("Sertifikat penandatangan berbeda") })
        assertTrue(diff.getValue("DNA").any { it.kind == ChangeKind.DITAMBAH && it.text == "Skema v3" })
        val sms = diff.getValue("Izin").single()
        assertTrue(sms.attention && sms.kind == ChangeKind.DITAMBAH && sms.text.startsWith("android.permission.READ_SMS ⚠"))
        assertTrue(diff.getValue("Komponen").single().let { it.attention && it.text == "service com.example.Sync exported: false → true" })
        assertTrue(diff.getValue("SDK & library").any { it.attention && it.text == "Google AdMob (Iklan)" })
        assertTrue(diff.getValue("SDK & library").any { it.text == "androidx.core:core: 1.0 → 1.1" })
        assertEquals(listOf("Total: 10.0 MiB → 20.0 MiB", "Kode DEX: 10.0 MiB → 20.0 MiB"), diff.getValue("Kulit & ukuran").map { it.text })
        val markdown = diffToMarkdown(base, update, compareReports(base, update, 0))
        assertTrue(markdown.contains("- + android.permission.READ_SMS"))
        assertTrue(markdown.contains("**(perhatikan)**"))
    }

    @Test fun baselineDropsHeavyParts() {
        val heavy = base.copy(manifestXml = "<manifest/>", code = CodeIndex(methods = listOf(CodeMethod("d", "o", "n", "a", "x", "", true, listOf("i"), 1, false)), packages = listOf("p"), definedClasses = 3))
        val slim = heavy.asBaseline()
        assertNull(slim.manifestXml)
        assertTrue(slim.code.methods.isEmpty())
        assertEquals(listOf("p"), slim.code.packages)
        assertEquals(3, slim.code.definedClasses)
    }
}
