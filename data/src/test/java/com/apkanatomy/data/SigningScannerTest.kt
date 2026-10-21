package com.apkanatomy.data

import com.apkanatomy.domain.SignatureScheme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Fixtures in resources/signing are real apksigner output; expected values below are what
 * `apksigner verify -v --print-certs` reports for them. Regenerate with make-fixtures.sh
 * (new keys mean new fingerprints).
 */
class SigningScannerTest {
    @get:Rule val temp = TemporaryFolder()
    private val original = "92c5497525771de7cc0c5c8bdbbb3248c0e041c2741f217e4ba40bda74c8da0b"
    private val rotated = "52a98fd4480acd3aa242b46ebc2e2413fba11880f186003158002b3012090c70"

    private fun fixture(name: String): File = temp.root.resolve(name).also { file ->
        file.writeBytes(javaClass.classLoader!!.getResourceAsStream("signing/$name")!!.readBytes())
    }

    @Test fun readsAllSchemesAndOneCertificate() {
        val apk = fixture("all.apk").also { fixture("all.apk.idsig") }
        val report = SigningScanner().scan(apk, File(apk.path + ".idsig"))
        assertEquals(setOf(V1, V2, V3, V4), report.schemes)
        val cert = report.certificates.single()
        assertEquals(original, cert.sha256)
        assertEquals("bc1993680440e108636466a4d3bd0d7230fd58fe", cert.sha1)
        assertEquals("99617cfa4c5df13f6c58c251c9943136", cert.md5)
        assertEquals("CN=APKAnatomy Test,O=APKAnatomy,C=ID", cert.subject)
        assertEquals("RSA" to 2048, cert.keyAlgorithm to cert.keySize)
        assertEquals(listOf("v1", "v2", "v3 (API 24+)"), cert.foundIn)
        assertFalse(report.keyRotation)
        assertTrue(report.warnings.isEmpty())
    }

    @Test fun v4NeedsTheIdsigFile() {
        val apk = fixture("all.apk")
        assertFalse(V4 in SigningScanner().scan(apk, File(apk.path + ".idsig")).schemes)
        assertFalse(SigningScanner().scan(apk, null).v4Checked)
    }

    @Test fun readsKeyRotationAcrossV3AndV31() {
        val report = SigningScanner().scan(fixture("rotated.apk"), null)
        // apksigner also adds v1/v2 for older devices; it only reports them unverified because v3 takes precedence.
        assertEquals(setOf(V1, V2, V3, V31), report.schemes)
        assertTrue(report.keyRotation)
        val byDigest = report.certificates.associateBy { it.sha256 }
        assertEquals(listOf("v1", "v2", "v3 (API 24–32)"), byDigest.getValue(original).foundIn)
        assertEquals(listOf("v3.1 (API 33+)"), byDigest.getValue(rotated).foundIn)
        assertEquals("EC" to 256, byDigest.getValue(rotated).let { it.keyAlgorithm to it.keySize })
    }

    @Test fun readsV1OnlyAndUnsigned() {
        val v1 = SigningScanner().scan(fixture("v1only.apk"), null)
        assertEquals(setOf(V1), v1.schemes)
        assertEquals(listOf("v1"), v1.certificates.single().foundIn)
        val unsigned = SigningScanner().scan(fixture("unsigned.apk"), null)
        assertTrue(unsigned.schemes.isEmpty() && unsigned.certificates.isEmpty() && unsigned.warnings.isEmpty())
    }

    @Test fun corruptSigningBlockBecomesAWarning() {
        val apk = fixture("all.apk")
        val bytes = apk.readBytes()
        val magic = "APK Sig Block 42".toByteArray()
        // Block layout: size(8) | pairs | size(8) | magic(16); the trailing size excludes the leading one.
        val sizeField = (bytes.size - magic.size downTo 0).first { i -> bytes.copyOfRange(i, i + magic.size).contentEquals(magic) } - 8
        val size = (0..3).fold(0) { acc, i -> acc or ((bytes[sizeField + i].toInt() and 0xff) shl (8 * i)) }
        // Inflate the first ID-value pair length so it overruns the block.
        val firstPair = sizeField + 24 - size
        bytes[firstPair + 3] = 0x7f
        apk.writeBytes(bytes)
        val report = SigningScanner().scan(apk, null)
        assertTrue(report.warnings.single().startsWith("APK Signing Block tidak valid"))
        assertEquals(setOf(V1), report.schemes)
    }

    @Test fun nonZipFileBecomesWarnings() {
        val report = SigningScanner().scan(temp.newFile("x.apk").apply { writeText("bukan zip sama sekali, hanya teks") }, null)
        assertTrue(report.schemes.isEmpty())
        assertEquals(2, report.warnings.size)
    }
}
