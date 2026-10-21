package com.apkanatomy.data

import com.apkanatomy.axml.ResourceTable
import com.apkanatomy.domain.FilePreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewReaderTest {
    @get:Rule val temp = TemporaryFolder()
    private val demo by lazy {
        val bytes = javaClass.classLoader!!.getResourceAsStream("arsc/resources-demo.apk")!!.readBytes()
        temp.newFile("demo.apk").apply { writeBytes(bytes) }
    }

    @Test fun decodesBinaryXmlWithResourceNames() {
        val table = ResourceReader.read(demo).first
        val preview = PreviewReader.preview(demo, "res/xml/network_security_config.xml", table) as FilePreview.Text
        assertEquals("XML biner (didekode)", preview.kind)
        assertTrue(preview.text.contains("src=\"@raw/company_ca\""))
    }

    @Test fun showsTextAndRefusesMissingEntries() {
        val text = PreviewReader.preview(demo, "res/raw/company_ca.pem", null) as FilePreview.Text
        assertTrue(text.text.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(PreviewReader.preview(demo, "nope.txt", null) is FilePreview.Unavailable)
    }

    @Test fun classifiesImagesTextAndBinary() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertEquals("PNG", (PreviewReader.classify(png, true, null) as FilePreview.Image).format)
        assertTrue(PreviewReader.classify(png, false, null) is FilePreview.Hex)
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        assertEquals("WebP", (PreviewReader.classify(webp, true, null) as FilePreview.Image).format)
        assertEquals("Halo, dünia ✓\n", (PreviewReader.classify("Halo, dünia ✓\n".toByteArray(), true, null) as FilePreview.Text).text)
        val binary = PreviewReader.classify(ByteArray(40) { it.toByte() }, true, null) as FilePreview.Hex
        assertTrue(binary.dump.startsWith("0000  00 01 02 03"))
        assertEquals(3, binary.dump.lines().size)
        val classFile = PreviewReader.classify(byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(), 0, 0, 0, 0x34), true, null) as FilePreview.Hex
        assertTrue(classFile.note.startsWith("File class Java"))
    }

    @Test fun keepsTextCutInsideAMultiByteCharacter() {
        val bytes = ("a".repeat(PreviewReader.MAX_TEXT_BYTES - 1) + "é").toByteArray() // é straddles the limit
        val preview = PreviewReader.classify(bytes, true, null) as FilePreview.Text
        assertTrue(preview.truncated)
        assertEquals(PreviewReader.MAX_TEXT_BYTES - 1, preview.text.length)
    }

    @Test fun brokenBinaryXmlFallsBackToHex() {
        val broken = byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x7f, 0, 0, 0, 1, 2, 3)
        assertTrue((PreviewReader.classify(broken, true, null) as FilePreview.Hex).note.startsWith("XML biner tidak dapat didekode"))
    }
}
