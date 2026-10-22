package com.apkanatomy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ManifestReaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun apk(vararg entries: Pair<String, ByteArray>): File = temp.newFile("test.apk").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
    }

    @Test
    fun `decodes the manifest of an apk`() {
        val manifest = javaClass.classLoader!!.getResourceAsStream("sample_manifest.axml")!!.readBytes()
        val decoded = ManifestReader.read(apk("AndroidManifest.xml" to manifest))
        assertNull(decoded.note)
        assertTrue(decoded.xml!!.contains("package=\"com.example.sample\""))
        assertEquals("Sample & Co", decoded.application!!.attribute("label"))
    }

    @Test
    fun `explains a missing manifest`() {
        val (root, xml, note) = ManifestReader.read(apk("classes.dex" to byteArrayOf(1)))
        assertNull(root); assertNull(xml)
        assertEquals("AndroidManifest.xml tidak ditemukan di test.apk.", note)
    }

    @Test
    fun `explains a manifest that is not binary xml`() {
        val (_, xml, note) = ManifestReader.read(apk("AndroidManifest.xml" to "<manifest/>".toByteArray()))
        assertNull(xml)
        assertTrue(note!!.startsWith("Manifest tidak dapat didekode"))
    }

    @Test
    fun `explains a file that is not a zip`() {
        val (_, xml, note) = ManifestReader.read(temp.newFile("broken.apk").apply { writeText("not a zip") })
        assertNull(xml)
        assertTrue(note!!.startsWith("Manifest tidak dapat dibaca"))
    }
}
