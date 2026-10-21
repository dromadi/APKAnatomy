package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class SecurityNotesTest {
    private val now = 1_800_000_000_000L
    private fun cert(subject: String = "CN=Dev", notAfter: Long = now + 1, algorithm: String = "SHA256withRSA") =
        SignerCertificate(subject, subject, "1", 0, notAfter, algorithm, "RSA", 2048, "a", "b", "c", listOf("v2"))
    private val flags = SecurityFlags(debuggable = false, allowBackup = false, cleartextTraffic = false, testOnly = false, targetSdk = 35)
    private fun titles(flags: SecurityFlags?, signing: SigningReport) = securityNotes(flags, signing, now).map { it.level to it.title }

    @Test fun unsignedApkIsFlagged() {
        assertEquals(NoteLevel.PERHATIAN to "Tidak bertanda tangan", titles(null, SigningReport()).single())
    }
    @Test fun unreadableBlockIsNotCalledUnsigned() {
        assertEquals("Tanda tangan tidak terbaca", titles(null, SigningReport(warnings = listOf("rusak"))).single().second)
    }
    @Test fun v1OnlyWithModernTargetIsFlagged() {
        val notes = titles(flags, SigningReport(setOf(SignatureScheme.V1)))
        assertEquals(NoteLevel.PERHATIAN to "Hanya v1 untuk target SDK 35", notes.first())
        assertEquals(NoteLevel.INFO to "Hanya skema v1", titles(flags.copy(targetSdk = 29), SigningReport(setOf(SignatureScheme.V1))).first())
    }
    @Test fun debugExpiredAndWeakCertificatesAreNoted() {
        val signing = SigningReport(setOf(SignatureScheme.V2), listOf(cert("CN=Android Debug,O=Android,C=US", notAfter = now - 1, algorithm = "SHA1withRSA")), keyRotation = true)
        val notes = titles(null, signing).map { it.second }
        assertEquals(listOf("Tanda tangan modern", "Rotasi kunci", "Sertifikat debug Android", "Sertifikat kedaluwarsa", "Algoritma sertifikat lama"), notes)
    }
    @Test fun manifestFlagsAreExplained() {
        val risky = titles(flags.copy(debuggable = true, allowBackup = true, cleartextTraffic = true, testOnly = true), SigningReport(setOf(SignatureScheme.V3)))
        assertEquals(listOf(NoteLevel.BAIK, NoteLevel.PERHATIAN, NoteLevel.INFO, NoteLevel.PERHATIAN, NoteLevel.INFO), risky.map { it.first })
        val cleartext = securityNotes(flags.copy(cleartextTraffic = true, networkSecurityConfig = "@0x7f120000"), SigningReport(setOf(SignatureScheme.V3)), now)
            .single { it.title == "Lalu lintas HTTP diizinkan" }
        assertTrue(cleartext.explanation.contains("@0x7f120000"))
    }
}
