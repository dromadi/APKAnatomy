package com.apkanatomy.axml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are real aapt2 output, compiled from the `.xml` files next to them:
 *
 * ```
 * aapt2 compile -o res.zip --dir res        # res/layout/sample.xml = sample_layout.xml
 * aapt2 link --manifest sample_manifest.xml -I $ANDROID_HOME/platforms/android-35/android.jar -o out.apk res.zip
 * unzip out.apk AndroidManifest.xml res/layout/sample.xml
 * ```
 *
 * aapt2 writes the manifest string pool as UTF-16 and the layout pool as UTF-8, so both
 * encodings are covered.
 */
class AxmlDecoderTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }.readBytes()

    private val manifest by lazy { AxmlDecoder.decode(fixture("sample_manifest.axml")) }

    @Test
    fun `decodes manifest root and plain attributes`() {
        assertEquals("manifest", manifest.name)
        assertEquals("com.example.sample", manifest.attribute("package", namespaceUri = null))
        assertEquals("42", manifest.attribute("versionCode"))
        assertEquals("1.2.3-δ", manifest.attribute("versionName"))
        assertEquals(mapOf("android" to AxmlDecoder.ANDROID_NAMESPACE), manifest.namespaceDeclarations)
    }

    @Test
    fun `decodes nested elements in order`() {
        val sdk = manifest.childElements("uses-sdk").single()
        assertEquals("26", sdk.attribute("minSdkVersion"))
        assertEquals("35", sdk.attribute("targetSdkVersion"))

        val permissions = manifest.childElements("uses-permission").map { it.attribute("name") }
        assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA"), permissions)

        val application = manifest.childElements("application").single()
        assertEquals("Sample & Co", application.attribute("label"))
        assertEquals("true", application.attribute("debuggable"))
        assertEquals("false", application.attribute("allowBackup"))

        val activities = application.childElements("activity")
        assertEquals(listOf(".MainActivity", ".InternalActivity"), activities.map { it.attribute("name") })
        assertEquals(listOf("true", "false"), activities.map { it.attribute("exported") })

        val action = activities[0].childElements("intent-filter").single().childElements("action").single()
        assertEquals("android.intent.action.MAIN", action.attribute("name"))

        val service = application.childElements("service").single()
        assertEquals("com.example.sample.BIND_SYNC", service.attribute("permission"))
    }

    @Test
    fun `formats typed values`() {
        val metaData = manifest.childElements("application").single().childElements("meta-data")
            .associate { it.attribute("name") to it.attribute("value") }
        assertEquals("16dp", metaData["pad"])
        assertEquals("0.5", metaData["ratio"])
        assertEquals("#ff112233", metaData["color"])

        val layout = AxmlDecoder.decode(fixture("sample_layout.axml"))
        assertEquals("TextView", layout.name)
        assertEquals("16dip", layout.attribute("layout_width"))
        assertEquals("-1", layout.attribute("layout_height"))
        assertEquals("12.5sp", layout.attribute("textSize"))
        assertEquals("@0x0104000a", layout.attribute("text"))
    }

    @Test
    fun `exposes attribute resource ids`() {
        val name = manifest.childElements("uses-permission").first().attributes.single()
        assertEquals(0x01010003, name.resourceId)
        val packageAttribute = manifest.attributes.single { it.name == "package" }
        assertNull(packageAttribute.resourceId)
    }

    @Test
    fun `renders readable xml`() {
        val xml = manifest.toXmlString()
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest\n"))
        assertTrue(xml.contains("    xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"))
        assertTrue(xml.contains("android:label=\"Sample &amp; Co\""))
        assertTrue(xml.contains("<activity\n            android:name=\".InternalActivity\"\n            android:exported=\"false\" />"))
        assertTrue(xml.trimEnd().endsWith("</manifest>"))
    }

    @Test
    fun `rejects input that is not binary xml`() {
        assertThrows(AxmlException::class.java) { AxmlDecoder.decode(ByteArray(0)) }
        assertThrows(AxmlException::class.java) { AxmlDecoder.decode("<manifest/>".toByteArray()) }
    }

    @Test
    fun `rejects truncated and corrupted files without crashing`() {
        val bytes = fixture("sample_manifest.axml")
        for (length in listOf(8, 100, bytes.size / 2, bytes.size - 1)) {
            val truncated = bytes.copyOf(length)
            // The header still claims the full size; the decoder must notice instead of over-reading.
            assertThrows("length $length", AxmlException::class.java) { AxmlDecoder.decode(truncated) }
        }
        val corrupted = bytes.copyOf().also { it[12] = 0x7f; it[13] = 0x7f; it[14] = 0x7f; it[15] = 0x7f }
        assertThrows(AxmlException::class.java) { AxmlDecoder.decode(corrupted) }
    }
}
