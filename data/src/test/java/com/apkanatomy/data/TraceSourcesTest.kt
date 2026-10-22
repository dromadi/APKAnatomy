package com.apkanatomy.data

import com.apkanatomy.axml.AxmlDecoder
import com.apkanatomy.domain.TraceCollector
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Secret-shaped values are assembled at runtime so none appears literally in the source. */
class TraceSourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private val awsKey = "AK" + "IA" + "IOSFODNN7EXAMPLE"

    private fun dexWithStrings(vararg strings: String): ByteArray {
        val source = temp.newFile("Demo.smali")
        source.writeText(".class public Lsample/Demo;\n.super Ljava/lang/Object;\n.method public static f()V\n.registers 1\n" +
            strings.joinToString("") { "const-string v0, \"$it\"\n" } + "return-void\n.end method\n")
        val dex = temp.root.resolve("classes.dex")
        assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.absolutePath; apiLevel = 26 }, source.absolutePath))
        return dex.readBytes()
    }

    private fun apk(entries: Map<String, ByteArray>): File = temp.newFile("t${entries.hashCode()}.apk").also { f ->
        ZipOutputStream(f.outputStream()).use { zip -> entries.forEach { (n, b) -> zip.putNextEntry(ZipEntry(n)); zip.write(b); zip.closeEntry() } }
    }

    @Test fun findsTracesInDexStringsAssetsResourcesAndMetaData() {
        val app = apk(mapOf(
            "classes.dex" to dexWithStrings("https://api.contoh.id/v2/login", awsKey),
            "assets/config.json" to "{\"cdn\":\"http://cdn.contoh.id/a.js\"}".toByteArray(),
            "assets/logo.png" to "https://ignored.contoh.id/".toByteArray(),
            "res/raw/endpoints.txt" to "wss://ws.contoh.id/socket".toByteArray()))
        val traces = TraceCollector()
        DexCodeScanner().scan(listOf(app), traces = traces)
        TraceSources.scanFiles(mapOf("base.apk" to app), traces)
        val demo = javaClass.classLoader!!.getResourceAsStream("arsc/resources-demo.apk")!!.readBytes().let { temp.newFile("demo.apk").apply { writeBytes(it) } }
        TraceSources.scanResources(ResourceReader.read(demo).first, traces)
        val manifest = AxmlDecoder.decode(javaClass.classLoader!!.getResourceAsStream("sample_manifest.axml")!!.readBytes())
        TraceSources.scanManifest(manifest.childElements("application").single(), traces)

        val report = traces.result()
        val hosts = report.hosts.associateBy { it.host }
        assertEquals(listOf("base.apk/classes.dex"), hosts.getValue("api.contoh.id").locations)
        assertTrue(hosts.getValue("cdn.contoh.id").cleartext)
        assertEquals(listOf("base.apk/res/raw/endpoints.txt"), hosts.getValue("ws.contoh.id").locations)
        assertNull(hosts["ignored.contoh.id"])
        assertEquals(listOf("base.apk/classes.dex"), report.secrets.single { it.type == "AWS access key ID" }.locations)
        assertTrue(report.scannedStrings > 10) // DEX strings, resource strings and meta-data values were all fed in
    }

    @Test fun stopsAtTheAssetBudget() {
        val big = ByteArray((TraceSources.MAX_FILE_BYTES - 1).toInt()) { 'a'.code.toByte() }
        val app = apk((1..5).associate { "assets/blob$it.txt" to big })
        val traces = TraceCollector()
        TraceSources.scanFiles(mapOf("base.apk" to app), traces)
        assertTrue(traces.result().notes.single().startsWith("Pemindaian aset berhenti"))
    }
}
