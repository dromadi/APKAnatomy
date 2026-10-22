package com.apkanatomy.data

import com.apkanatomy.domain.ArchiveEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StructureScannerTest {
    private fun header(size: Int = 160): ByteArray {
        val b = ByteArray(112)
        "dex\n035\u0000".toByteArray().copyInto(b)
        fun put(offset: Int, value: Int) { for (i in 0..3) b[offset + i] = (value ushr (8 * i)).toByte() }
        put(32, size); put(36, 112); put(40, 0x12345678)
        put(88, 2); put(92, 112); put(96, 1); put(100, 128)
        return b
    }
    @Test fun readsMethodReferencesAndClasses() {
        val m = parseDexHeader(header(), 160, "base.apk", "classes.dex")
        assertEquals(2L, m.methodReferences); assertEquals(1L, m.classDefinitions)
        assertNull(m.note)
    }
    @Test fun rejectsOutOfBoundsTables() {
        val b = header(); b[88] = 100
        assertNotNull(parseDexHeader(b, 160, "base.apk", "classes.dex").note)
    }
    @Test fun rejectsTruncatedAndUnsupportedHeaders() {
        assertNotNull(parseDexHeader(ByteArray(20), 20, "base.apk", "classes.dex").note)
        val b = header(); "041".toByteArray().copyInto(b, 4)
        assertNotNull(parseDexHeader(b, 160, "base.apk", "classes.dex").note)
    }
    @Test fun readsLibraryVersionFiles() {
        val apk = File.createTempFile("versions", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                put("META-INF/com.google.firebase_firebase-messaging.version", "24.1.0\n")
                put("META-INF/androidx.core_core.version", "1.13.1")
                put("META-INF/broken.version", "1.0")
                put("META-INF/androidx.junk_junk.version", "<script>")
                put("assets/META-INF/a_b.version", "9")
            }
            val versions = StructureScanner().scan(listOf(apk)).libraryVersions
            assertEquals(mapOf("androidx.core:core" to "1.13.1", "com.google.firebase:firebase-messaging" to "24.1.0"), versions)
        } finally { apk.delete() }
    }
    @Test fun hermesDoesNotAutomaticallyImplyReactNative() {
        val t = detectTechnologies(listOf(ArchiveEntry("base.apk", "lib/arm64-v8a/libhermes.so", 100, 90)))
        assertEquals(listOf("Hermes"), t.map { it.name })
    }
    @Test fun doesNotTreatAssetNamedFlutterAsRuntime() {
        assertTrue(detectTechnologies(listOf(ArchiveEntry("base.apk", "assets/libflutter.so", 100, 90))).isEmpty())
    }
    @Test fun scansSplitEvidenceAndRetainsArchiveIdentity() {
        val base = zip(mapOf("classes.dex" to (header() + ByteArray(48)), "assets/sample.txt" to byteArrayOf(1)))
        val split = zip(mapOf("lib/arm64-v8a/libflutter.so" to byteArrayOf(1)))
        try {
            val report = StructureScanner().scan(listOf(base, split))
            assertEquals(3, report.entries.size)
            assertEquals("arm64-v8a", report.libraries.single().abi)
            assertEquals("Flutter", report.technologies.single().name)
            assertEquals(2L, report.dex.single().methodReferences)
        } finally { base.delete(); split.delete() }
    }
    @Test fun capsEnumerationAndReportsPartialResults() {
        val file = zip(mapOf("assets/a" to byteArrayOf(1), "assets/b" to byteArrayOf(2)))
        try {
            val r = StructureScanner(1).scan(listOf(file))
            assertEquals(1, r.entries.size); assertTrue(r.warnings.isNotEmpty())
        } finally { file.delete() }
    }
    @Test fun corruptArchiveDoesNotHideReadableArchive() {
        val bad = File.createTempFile("bad", ".apk").apply { writeText("bad") }
        val good = zip(mapOf("assets/a" to byteArrayOf(1)))
        try {
            val r = StructureScanner().scan(listOf(bad, good))
            assertEquals(1, r.entries.size); assertTrue(r.warnings.isNotEmpty())
        } finally { bad.delete(); good.delete() }
    }
    private fun zip(entries: Map<String, ByteArray>): File {
        val f = File.createTempFile("fixture", ".apk")
        ZipOutputStream(f.outputStream()).use { zip ->
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
        }
        return f
    }
}
