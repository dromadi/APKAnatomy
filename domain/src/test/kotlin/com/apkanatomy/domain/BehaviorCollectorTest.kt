package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class BehaviorCollectorTest {
    private fun id(type: String, method: String = "run()V") = "base.apk:classes.dex:$type->$method"

    @Test fun matchesCallsByOwnerNameAndStrings() {
        val c = BehaviorCollector("com.example")
        assertTrue(c.watches("Landroid/telephony/TelephonyManager;"))
        assertFalse(c.watches("Ljava/lang/String;"))
        c.method(id("Lcom/example/A;"), listOf("Landroid/telephony/TelephonyManager;" to "getDeviceId", "Landroid/telephony/TelephonyManager;" to "getNetworkType"), emptyList(), 10)
        // Settings.Secure.getString counts only when "android_id" is loaded in the same method.
        c.method(id("Lcom/example/B;"), listOf("Landroid/provider/Settings\$Secure;" to "getString"), listOf("bluetooth_name"), 5)
        c.method(id("Llib/C;"), listOf("Landroid/provider/Settings\$Secure;" to "getString"), listOf("android_id"), 5)
        c.method(id("Llib/D;"), emptyList(), listOf("test-keys"), 2)
        val r = c.result()
        assertEquals(22, r.scannedInstructions)
        val byId = r.findings.associateBy { it.id }
        assertEquals(setOf("device-id", "android-id", "root-check"), byId.keys)
        // Identity comes before app protection.
        assertEquals("root-check", r.findings.last().id)
        assertEquals(listOf("TelephonyManager.getDeviceId"), byId.getValue("device-id").apis)
        assertEquals(listOf("Settings.Secure.getString"), byId.getValue("android-id").apis)
        assertEquals(listOf("\"test-keys\""), byId.getValue("root-check").apis)
        assertEquals(1, byId.getValue("device-id").inAppPackage)
        assertEquals(0, byId.getValue("android-id").inAppPackage)
    }

    @Test fun listsTheAppsOwnMethodsFirst() {
        val c = BehaviorCollector("com.example", maxUsedBy = 2)
        listOf("Llib/A;", "Llib/B;", "Llib/C;", "Lcom/example/Main;").forEach { c.method(id(it), listOf("Ljava/lang/Class;" to "forName"), emptyList(), 1) }
        val f = c.result().findings.single()
        assertEquals(4, f.count)
        assertEquals(1, f.inAppPackage)
        assertEquals(listOf(id("Lcom/example/Main;"), id("Llib/A;")), f.usedBy)
    }

    @Test fun riskyBehavioursBecomeDnaNotesAndBandingChanges() {
        val c = BehaviorCollector()
        c.pattern(BehaviorPatterns.TRUST_ALL, id("Lx/T;", "checkServerTrusted()V"))
        c.method(id("Lx/W;"), listOf("Landroid/webkit/WebSettings;" to "setJavaScriptEnabled"), emptyList(), 3)
        val behaviors = c.result()
        assertEquals(BehaviorPatterns.TRUST_ALL, behaviors.findings.first().id)
        assertEquals(listOf("Perilaku: TrustManager yang menerima semua sertifikat"), behaviors.securityNotes().map { it.title })
        val old = AnatomyReport("x", "X", "1", 26, 35, "-", emptyList(), emptyList())
        val new = old.copy(behaviors = behaviors)
        assertTrue(new.notes(0).any { it.title.startsWith("Perilaku:") && it.level == NoteLevel.PERHATIAN })
        val changes = compareReports(old, new, 0).single { it.title == "Perilaku" }.changes
        assertEquals(mapOf("Perilaku: JavaScript di WebView diaktifkan" to false, "Perilaku: TrustManager yang menerima semua sertifikat" to true),
            changes.associate { it.text to it.attention })
        assertTrue("TrustManager yang menerima semua sertifikat" in new.toMarkdown(0))
        assertTrue("\"behaviors\"" in new.toJson(0))
    }
}
