package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class AuditTest {
    private fun app(pkg: String, vararg perms: String, trackers: List<Pair<String, String>> = emptyList(), target: Int = 34,
        debuggable: Boolean = false, installer: String? = "com.android.vending", open: Int = 1, system: Boolean = false) =
        AppAudit(pkg, pkg, system, "1", target, debuggable, false, false, installer, perms.map { "android.permission.$it" }, open, trackers)

    @Test fun reasonsAndPoints() {
        val risky = app("a", "CAMERA", "ACCESS_FINE_LOCATION", trackers = listOf("Google AdMob" to "Iklan", "AppLovin" to "Iklan", "Firebase Analytics" to "Analitik"),
            target = 26, debuggable = true, installer = "com.android.packageinstaller", open = 3)
        assertEquals(listOf(AUDIT_DEBUG, AUDIT_OLD, AUDIT_TRACKERS, AUDIT_TRACKERS, AUDIT_DATA, AUDIT_EXPOSED, AUDIT_SIDELOAD), risky.reasons().map { it.filter })
        assertEquals(3 + 2 + 2 + 1 + 2 + 1 + 1, risky.points())
        assertEquals("Pelacak Iklan: Google AdMob, AppLovin", risky.reasons()[2].text)
        // A store app with only its launcher open has nothing to report.
        assertTrue(app("b").reasons().isEmpty())
        assertTrue(app("c", installer = null, system = true).reasons().isEmpty())
    }

    @Test fun summaryCountsAppsPerFilter() {
        val summary = listOf(app("a", "CAMERA"), app("b", trackers = listOf("Sentry" to "Pelaporan crash")), app("c", "READ_SMS", target = 22)).auditSummary()
        assertEquals(2, summary[AUDIT_DATA]); assertEquals(1, summary[AUDIT_TRACKERS]); assertEquals(1, summary[AUDIT_OLD]); assertEquals(0, summary[AUDIT_DEBUG])
        assertEquals("Iklan", trackerPurpose("Google AdMob"))
        assertEquals(listOf("Lokasi", "Kamera"), dataCategories(listOf("android.permission.CAMERA", "android.permission.ACCESS_COARSE_LOCATION")))
    }
}
