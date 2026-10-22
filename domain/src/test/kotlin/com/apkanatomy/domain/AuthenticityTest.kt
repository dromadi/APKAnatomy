package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class AuthenticityTest {
    private val installed = InstalledCopy("2.0", 20, signers = listOf("aa"), pastSigners = listOf("old"), installer = "com.android.vending")

    @Test fun sameSignerIsGoodAndVersionIsCompared() {
        val notes = authenticityNotes("Contoh", listOf("aa"), 21, installed)
        assertEquals(listOf(NoteLevel.BAIK, NoteLevel.INFO), notes.map { it.level })
        assertEquals("Versi file lebih baru dari yang terpasang", notes[1].title)
        assertTrue("Google Play (com.android.vending)" in notes[0].explanation)
    }

    @Test fun differentSignerNeedsAttention() {
        val notes = authenticityNotes("Contoh", listOf("bb"), 19, installed)
        assertEquals(NoteLevel.PERHATIAN, notes[0].level)
        assertEquals("Versi file lebih lama dari yang terpasang", notes[1].title)
    }

    @Test fun rotatedKeyAndUnsignedFilesAreExplained() {
        assertEquals("Ditandatangani kunci lama aplikasi terpasang", authenticityNotes("Contoh", listOf("old"), 0, installed).single().title)
        assertEquals("Keaslian tidak dapat dibandingkan", authenticityNotes("Contoh", emptyList(), 20, installed).first().title)
    }

    @Test fun reportNotesIncludeAuthenticityAndInstallSource() {
        val cert = SignerCertificate("CN=x", "CN=x", "1", 0, Long.MAX_VALUE, "SHA256withRSA", "RSA", 2048, "bb", "s1", "m5", listOf("v2"))
        val file = AnatomyReport("x", "X", "1", 26, 35, "File APK lokal", emptyList(), emptyList(),
            signing = SigningReport(setOf(SignatureScheme.V2), listOf(cert)), versionCode = 20, installedCopy = installed)
        assertTrue(file.notes(0).any { it.title == "Penandatangan berbeda dari aplikasi terpasang" })
        assertTrue("\"installedCopy\"" in file.toJson(0))
        val app = AnatomyReport("x", "X", "1", 26, 35, "Aplikasi terinstal", emptyList(), emptyList(), installer = null)
        assertEquals("Dipasang dari tidak tercatat (adb, bawaan sistem, atau tidak diketahui). Aplikasi dari toko resmi melewati pemeriksaan toko tersebut; file APK dari sumber lain sebaiknya dicek penandatangannya.",
            app.notes(0).single { it.title == "Sumber pemasangan" }.explanation)
        assertEquals("F-Droid (org.fdroid.fdroid)", describeInstaller("org.fdroid.fdroid"))
    }
}
