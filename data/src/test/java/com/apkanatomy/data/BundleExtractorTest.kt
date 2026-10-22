package com.apkanatomy.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Fixtures hold apksigner-signed APKs built with aapt2: a base, an arm64 split (k1) and an xxhdpi split (k2). */
class BundleExtractorTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(name: String) = javaClass.classLoader!!.getResourceAsStream("bundle/$name")!!.readBytes().let { bytes ->
        temp.newFile(name).apply { writeBytes(bytes) }
    }

    @Test fun extractsBundletoolApks() {
        val result = BundleExtractor().extract(fixture("demo.apks"), temp.newFolder("out"))
        assertEquals("APKS (bundletool)", result.format)
        assertEquals("base-master.apk", result.base.name)
        assertEquals(listOf("base-arm64_v8a.apk"), result.splits.map { it.name })
        assertEquals(listOf("standalones/standalone-arm64_v8a.apk: varian standalone untuk perangkat lama dilewati."), result.skipped)
    }

    @Test fun extractsXapkSkippingObb() {
        val result = BundleExtractor().extract(fixture("demo.xapk"), temp.newFolder("out"))
        assertEquals("XAPK", result.format)
        assertEquals("com.example.bundle.apk", result.base.name)
        assertEquals(listOf("config.arm64_v8a.apk", "config.xxhdpi.apk"), result.splits.map { it.name })
        assertTrue(result.skipped.single().contains("OBB"))
    }

    @Test fun flagsSplitsSignedByAnotherKey() {
        val result = BundleExtractor().extract(fixture("demo.xapk"), temp.newFolder("out"))
        val signing = scanSigning(result.base, result.splits, v4Signature = null)
        assertEquals(listOf("config.xxhdpi.apk"), signing.mismatchedSplits)
    }

    @Test fun neverWritesOutsideTheOutputFolder() {
        val evil = temp.newFile("evil.apks")
        val apk = fixture("demo.apks").let { java.util.zip.ZipFile(it).use { z -> z.getInputStream(z.getEntry("splits/base-master.apk")).readBytes() } }
        ZipOutputStream(evil.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../../escape.apk")); zip.write(apk); zip.closeEntry()
            zip.putNextEntry(ZipEntry("a/escape.apk")); zip.write(apk); zip.closeEntry()
        }
        val out = temp.newFolder("out")
        // Both entries are complete base APKs, so the bundle is ambiguous; what matters is where they were written.
        assertThrows(BundleExtractor.BundleException::class.java) { BundleExtractor().extract(evil, out) }
        assertEquals(setOf("escape.apk", "escape-1.apk"), out.list()!!.toSet())
        assertFalse(temp.root.resolve("escape.apk").exists())
    }

    @Test fun rejectsBundlesWithoutApksOrOverLimits() {
        val empty = temp.newFile("empty.xapk").also { f -> ZipOutputStream(f.outputStream()).use { it.putNextEntry(ZipEntry("manifest.json")); it.write(1); it.closeEntry() } }
        assertThrows(BundleExtractor.BundleException::class.java) { BundleExtractor().extract(empty, temp.newFolder("a")) }
        val xapk = fixture("demo.xapk")
        assertThrows(BundleExtractor.BundleException::class.java) { BundleExtractor(maxApkBytes = 1000).extract(xapk, temp.newFolder("b")) }
        assertThrows(BundleExtractor.BundleException::class.java) { BundleExtractor(maxApks = 1).extract(xapk, temp.newFolder("c")) }
    }
}
