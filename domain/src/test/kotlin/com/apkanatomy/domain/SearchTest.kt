package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    private val method = CodeMethod("Lcom/x/Login;->submit()V", "Lcom/x/Login;", "submit", "base.apk", "classes.dex", "public", true, emptyList(), 0, false)
    private val report = AnatomyReport("com.x", "X", "1", 26, 35, "-",
        components = listOf(AppComponent("com.x.LoginActivity", ComponentKind.ACTIVITY, true, true, null)),
        permissions = listOf(AppPermission("android.permission.CAMERA", "Dangerous")),
        code = CodeIndex(classes = listOf(CodeClass("Lcom/x/Login;", null, emptyList(), "base.apk", "classes.dex")), methods = listOf(method),
            strings = listOf(StringUse(method.id, "https://api.x.com/login"))),
        manifestXml = "<manifest>\n  <activity android:name=\"com.x.LoginActivity\" />\n</manifest>")

    @Test fun findsMatchesAcrossSectionsAndPointsIntoCode() {
        val results = report.searchAll("login").toMap()
        assertEquals(listOf("Komponen", "Kode", "Manifest"), report.searchAll("login").map { it.first })
        val code = results.getValue("Kode")
        assertEquals("Lcom/x/Login;", code.first { it.classId != null }.title.let { "L" + it.replace('.', '/') + ";" })
        assertEquals(method.id, code.first { it.detail.startsWith("method") }.methodId)
        assertEquals(method.id, code.first { it.detail.startsWith("string") }.methodId)
        assertEquals("baris 2", results.getValue("Manifest").single().detail)
    }

    @Test fun ignoresShortQueriesAndCapsEachSection() {
        assertTrue(report.searchAll("l").isEmpty())
        assertEquals(1, report.searchAll("camera").toMap().getValue("Izin").size)
        assertEquals(1, report.searchAll("o", perSection = 1).size + 1)
        val many = report.copy(permissions = List(50) { AppPermission("p.CAMERA$it", "Normal") })
        assertEquals(30, many.searchAll("camera").toMap().getValue("Izin").size)
    }
}
