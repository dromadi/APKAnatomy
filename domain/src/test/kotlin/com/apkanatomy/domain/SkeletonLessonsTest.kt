package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class SkeletonLessonsTest {
    private fun filter(actions: List<String>, categories: List<String> = emptyList(), schemes: List<String> = emptyList(),
        mimeTypes: List<String> = emptyList(), autoVerify: Boolean = false) =
        IntentFilterInfo(actions.map { "android.intent.action.$it" }, categories.map { "android.intent.category.$it" },
            schemes, mimeTypes = mimeTypes, autoVerify = autoVerify)

    @Test fun recognisesCommonFilters() {
        assertTrue(filter(listOf("MAIN"), listOf("LAUNCHER")).lesson()!!.startsWith("Titik masuk launcher"))
        assertTrue(filter(listOf("VIEW"), listOf("BROWSABLE"), listOf("https"), autoVerify = true).lesson()!!.startsWith("App Link"))
        assertTrue(filter(listOf("VIEW"), listOf("BROWSABLE"), listOf("HTTP")).lesson()!!.startsWith("Deep link web"))
        assertTrue(filter(listOf("VIEW"), schemes = listOf("myapp")).lesson()!!.startsWith("Deep link:"))
        assertTrue(filter(listOf("VIEW"), schemes = listOf("content"), mimeTypes = listOf("application/pdf")).lesson()!!.startsWith("Pembuka file"))
        assertTrue(filter(listOf("SEND"), mimeTypes = listOf("image/*")).lesson()!!.startsWith("Target berbagi"))
        assertNull(filter(listOf("MAIN")).lesson())
        assertTrue(filter(listOf("VIEW"), schemes = listOf("myapp")).isDeepLink)
        assertFalse(filter(listOf("VIEW"), schemes = listOf("file")).isDeepLink)
        assertFalse(filter(listOf("VIEW")).isDeepLink)
    }

    @Test fun recognisesRolesAndSensitivePermissions() {
        val accessibility = AppComponent("A", ComponentKind.SERVICE, true, true, "android.permission.BIND_ACCESSIBILITY_SERVICE")
        assertTrue(accessibility.role()!!.startsWith("Layanan aksesibilitas"))
        assertNull(accessibility.copy(permission = null).role())
        assertNotNull(sensitivePermissionLesson("android.permission.SYSTEM_ALERT_WINDOW"))
        assertNull(sensitivePermissionLesson("android.permission.INTERNET"))
    }
}
