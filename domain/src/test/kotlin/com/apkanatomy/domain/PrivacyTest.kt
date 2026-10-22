package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class PrivacyTest {
    private val base = AnatomyReport("com.example", "Contoh", "1", 26, 35, "x", emptyList(), emptyList())

    @Test fun emptyAppHasNothingToReport() {
        val profile = base.privacyProfile()
        assertTrue(profile.trackers.isEmpty() && profile.dataAccess.isEmpty() && !profile.usesAdvertisingId)
        assertEquals("Tidak ada SDK pelacak yang dikenali. Tidak meminta akses ke kategori data pribadi yang umum.", profile.summary)
    }

    @Test fun combinesTrackersPermissionsAndHosts() {
        val report = base.copy(
            permissions = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.CAMERA", "android.permission.INTERNET",
                "com.google.android.gms.permission.AD_ID", "android.permission.QUERY_ALL_PACKAGES").map { AppPermission(it, "x") },
            sdks = listOf(DetectedSdk("Google AdMob", "Iklan", "", listOf("com.google.android.gms.ads"), 1),
                DetectedSdk("OkHttp", "Jaringan", "", listOf("okhttp3"), 1), DetectedSdk("Firebase Crashlytics", "Firebase", "", listOf("c"), 1)),
            traces = TraceCollector().apply { scan("https://googleads.g.doubleclick.net/x", "dex"); scan("https://api.contoh.id/v1", "dex") }.result())
        val p = report.privacyProfile()
        assertEquals(listOf("Google AdMob" to "Iklan", "Firebase Crashlytics" to "Pelaporan crash"), p.trackers.map { it.name to it.purpose })
        assertEquals(listOf("Lokasi", "Kamera", "Aplikasi terpasang", "ID iklan"), p.dataAccess.map { it.category })
        assertFalse(p.dataAccess.single { it.category == "ID iklan" }.runtimePrompt)
        assertEquals(listOf("googleads.g.doubleclick.net"), p.trackerHosts)
        assertTrue(p.usesAdvertisingId)
        assertEquals("2 SDK pelacak dibundel (Iklan, Pelaporan crash). Dapat meminta akses ke: lokasi, kamera, aplikasi terpasang, id iklan. Membaca ID iklan.", p.summary)
    }
}
