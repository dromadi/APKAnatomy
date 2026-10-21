package com.apkanatomy.data

import com.apkanatomy.domain.NscScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** resources-demo.apk is aapt2 output of core/axml/src/test/resources/arsc (see the README there). */
class ResourceReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val apk by lazy {
        val bytes = javaClass.classLoader!!.getResourceAsStream("arsc/resources-demo.apk")!!.readBytes()
        temp.newFile("demo.apk").apply { writeBytes(bytes) }
    }

    @Test fun resolvesManifestReferencesAndReadsNetworkSecurityConfig() {
        val (table, note) = ResourceReader.read(apk)
        assertNull(note)
        val manifest = ManifestReader.read(apk, table)
        assertEquals("@string/app_name", manifest.application!!.attribute("label"))
        val (nsc, nscNote) = ResourceReader.networkSecurityConfig(apk, manifest.application, table)
        assertNull(nscNote)
        nsc!!
        assertEquals("res/xml/network_security_config.xml", nsc.file)
        assertEquals(NscScope(cleartextPermitted = false, trustAnchors = listOf("system", "user")), nsc.base)
        assertEquals(NscScope(listOf("*.legacy.example.com", "10.0.2.2"), cleartextPermitted = true), nsc.domains[0])
        assertEquals(NscScope(listOf("*.api.example.com"), trustAnchors = listOf("@raw/company_ca"), pins = 2, pinExpiration = "2030-01-01", certificateTransparency = false), nsc.domains[1])
        assertEquals(listOf("user"), nsc.debugOverrides!!.trustAnchors)
    }

    @Test fun withoutTableTheConfigCannotBeLocated() {
        val manifest = ManifestReader.read(apk)
        val (nsc, note) = ResourceReader.networkSecurityConfig(apk, manifest.application, null)
        assertNull(nsc)
        assertTrue(note!!.contains("tanpa resources.arsc"))
    }

    @Test fun missingTableIsNotAnErrorButABrokenOneIs() {
        val noTable = temp.newFile("none.apk").also { f -> ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("classes.dex")); it.write(1); it.closeEntry() } }
        assertEquals(null to null, ResourceReader.read(noTable))
        val broken = temp.newFile("broken.apk").also { f -> ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("resources.arsc")); it.write(ByteArray(64)); it.closeEntry() } }
        val (table, note) = ResourceReader.read(broken)
        assertNull(table)
        assertTrue(note!!.startsWith("resources.arsc tidak dapat dibaca"))
    }
}
