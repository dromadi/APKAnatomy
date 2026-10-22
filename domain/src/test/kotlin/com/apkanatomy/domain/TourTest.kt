package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class TourTest {
    @Test fun stepsDescribeThisReportAndPointToRealTabs() {
        val report = AnatomyReport("com.x", "X", "1", 26, 35, "-",
            components = listOf(AppComponent("com.x.Main", ComponentKind.ACTIVITY, true, true, null,
                intentFilters = listOf(IntentFilterInfo(listOf("android.intent.action.MAIN"), listOf("android.intent.category.LAUNCHER"))))),
            permissions = emptyList(), code = CodeIndex(classes = listOf(CodeClass("Lcom/x/Main;", null, emptyList(), "base.apk", "classes.dex")), definedClasses = 1))
        val tour = report.tour()
        assertEquals(8, tour.size)
        val tabs = setOf("Ringkasan", "Belajar", "Cari", "Privasi", "Perilaku", "Teknologi", "Komponen", "Izin", "Kemampuan", "Isi APK", "Kode", "Manifest", "DNA", "Kulit", "Jejak")
        assertTrue(tour.all { it.tab in tabs && it.question.endsWith("?") && it.answer.isNotBlank() })
        assertTrue(tour[1].observation.contains("Layar pertama: Main"))
        assertTrue(tour.last().observation.contains("Buka class com.x.Main"))
        assertEquals("APK ini tidak memiliki sertifikat penandatangan yang terbaca.", tour[0].observation)
    }
}
