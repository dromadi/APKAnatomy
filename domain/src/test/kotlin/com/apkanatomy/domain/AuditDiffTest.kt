package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class AuditDiffTest {
    private fun app(pkg: String, vararg perms: String, version: String = "1", trackers: List<String> = emptyList(), system: Boolean = false,
        installer: String? = "com.android.vending", debuggable: Boolean = false, note: String? = null) =
        AppAudit(pkg, pkg.uppercase(), system, version, 34, debuggable, false, false, installer, perms.map { "android.permission.$it" }, 1,
            trackers.map { it to "x" }, note)

    @Test fun reportsInstalledRemovedAndChangedApps() {
        val old = AuditSnapshot(100, includeSystem = false, apps = listOf(
            app("same"), app("gone", version = "3"), app("upd", "INTERNET", version = "1.0", trackers = listOf("Sentry")),
            app("perm", "CAMERA"), app("side")))
        val new = AuditSnapshot(200, includeSystem = false, apps = listOf(
            app("same"), app("fresh", "READ_CONTACTS", trackers = listOf("Google AdMob")),
            app("upd", "INTERNET", "ACCESS_FINE_LOCATION", version = "2.0", trackers = listOf("Firebase Analytics")),
            app("perm"), app("side", installer = "com.android.packageinstaller")))
        val diff = auditChanges(old, new)
        assertEquals(100, diff.since)
        val byPkg = diff.changes.associateBy { it.packageName }
        assertEquals(setOf("gone", "fresh", "upd", "perm", "side"), byPkg.keys)
        assertEquals(CHANGE_NEW, byPkg.getValue("fresh").kind)
        assertEquals(listOf("versi 1, dari Google Play (com.android.vending)", "Akses data: Kontak & akun", "Pelacak: Google AdMob"), byPkg.getValue("fresh").details)
        assertEquals(CHANGE_REMOVED, byPkg.getValue("gone").kind)
        assertFalse(byPkg.getValue("gone").attention)
        val upd = byPkg.getValue("upd")
        assertEquals(CHANGE_UPDATED, upd.kind)
        assertEquals(listOf("versi 1.0 → 2.0", "Izin baru: ACCESS_FINE_LOCATION", "Akses data baru: Lokasi", "Pelacak baru: Firebase Analytics", "Pelacak hilang: Sentry"), upd.details)
        assertTrue(upd.attention)
        // Dropping a permission is not a reason for attention; a new install source is.
        assertEquals(CHANGE_CHANGED, byPkg.getValue("perm").kind)
        assertFalse(byPkg.getValue("perm").attention)
        assertTrue(byPkg.getValue("side").attention)
        // Attention first, then new before updated before changed before removed.
        assertEquals(listOf("fresh", "upd", "side", "perm", "gone"), diff.changes.map { it.packageName })
    }

    @Test fun ignoresSystemAppsAndUnreadableCodeWhenAuditsDiffer() {
        val old = AuditSnapshot(1, includeSystem = true, apps = listOf(app("sys", system = true), app("a", trackers = listOf("Sentry"))))
        val new = AuditSnapshot(2, includeSystem = false, apps = listOf(app("a", note = "Kode tidak terbaca")))
        assertTrue(auditChanges(old, new).changes.isEmpty())
        val both = auditChanges(old, old.copy(apps = listOf(app("a", trackers = listOf("Sentry")))))
        assertEquals(listOf("sys" to CHANGE_REMOVED), both.changes.map { it.packageName to it.kind })
    }
}
