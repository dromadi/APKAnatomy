package com.apkanatomy.data

import com.apkanatomy.domain.BehaviorCollector
import com.apkanatomy.domain.BehaviorPatterns
import org.junit.Assert.*
import org.junit.Test
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.nio.file.Files
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DexCodeScannerTest {
    @Test fun indexesDefinitionsAndDirectInvocations() = fixture { apk ->
        val result = DexCodeScanner().scan(listOf(apk))
        assertTrue(result.warnings.toString(), result.warnings.isEmpty())
        assertEquals(1, result.classes.size)
        assertEquals(2, result.methods.size)
        val caller = result.methods.single { it.name == "start" }
        val target = result.methods.single { it.name == "download" }
        assertEquals(caller.id, result.callersOf(target.descriptor).single().callerId)
        assertEquals(target.descriptor, result.callsFrom(caller.id).single().target)
        assertTrue(caller.instructions.any { "invoke-static" in it && "download" in it })
    }
    @Test fun instructionLimitMarksIncompleteUsage() = fixture { apk ->
        val result = DexCodeScanner(CodeLimits(visitedInstructions = 1)).scan(listOf(apk))
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.methods.any { it.truncated })
    }
    @Test fun skipsOversizedDexWithoutParsing() = fixture { apk ->
        val result = DexCodeScanner(CodeLimits(dexBytes = 112)).scan(listOf(apk))
        assertTrue(result.classes.isEmpty()); assertTrue(result.warnings.isNotEmpty())
    }
    @Test fun collectsPackagesBeyondTheClassLimit() = fixture({ root ->
        listOf("Lokhttp3/OkHttpClient;", "Lcom/example/app/a;", "Lcom/example/app/MainActivity;").forEachIndexed { i, type ->
            File(root, "Extra$i.smali").writeText(".class public $type\n.super Ljava/lang/Object;\n")
        }
    }) { apk ->
        val result = DexCodeScanner(CodeLimits(classes = 1)).scan(listOf(apk))
        assertEquals(1, result.classes.size)
        assertEquals(listOf("com.example.app", "okhttp3", "sample"), result.packages)
        assertEquals(4, result.definedClasses)
        assertEquals(1, result.shortNamedClasses)
    }
    @Test fun rejectsCorruptDex() {
        val apk = File.createTempFile("corrupt", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("classes.dex")); zip.write(ByteArray(112)); zip.closeEntry() }
            val result = DexCodeScanner().scan(listOf(apk))
            assertTrue(result.classes.isEmpty()); assertTrue(result.warnings.isNotEmpty())
        } finally { apk.delete() }
    }
    @Test fun separatesCallerIdentityAcrossArchives() = fixture { apk ->
        val split = File.createTempFile("split", ".apk")
        try {
            apk.copyTo(split, overwrite = true)
            val result = DexCodeScanner().scan(listOf(apk, split))
            val ids = result.methods.filter { it.name == "start" }.map { it.id }
            assertEquals(2, ids.distinct().size)
            assertEquals(2, result.callersOf("Lsample/Demo;->download()V").size)
        } finally { split.delete() }
    }
    @Test fun findsSensitiveBehavioursInsideAndBeyondTheIndex() = fixture(::writeBehaviourClasses) { apk ->
        fun scan(limits: CodeLimits) = BehaviorCollector("sample").also { DexCodeScanner(limits).scan(listOf(apk), "sample", behaviors = it) }.result()
        val full = scan(CodeLimits())
        val byId = full.findings.associateBy { it.id }
        assertEquals(setOf(BehaviorPatterns.TRUST_ALL, BehaviorPatterns.HOSTNAME_ALL, "ssl-proceed", "ecb", "root-check", "js-bridge"), byId.keys)
        assertEquals(listOf("Lsample/TrustAll;->checkServerTrusted([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V"),
            byId.getValue(BehaviorPatterns.TRUST_ALL).usedBy.map { it.substringAfter("classes.dex:") })
        // "AES" for a key spec next to "AES/GCM/NoPadding", and RSA/ECB, are not ECB encryption.
        assertEquals(listOf("Lsample/Crypto;->weak()V"), byId.getValue("ecb").usedBy.map { it.substringAfter("classes.dex:") })
        assertEquals(1, byId.getValue("js-bridge").inAppPackage)
        assertTrue(full.findings.all { it.count == 1 })
        assertTrue(byId.getValue("ssl-proceed").level == com.apkanatomy.domain.NoteLevel.PERHATIAN)
        // Classes outside the index, or cut short by the instruction limit, are read by the extra pass exactly once.
        assertEquals(full.findings, scan(CodeLimits(classes = 1)).findings)
        assertEquals(full.findings, scan(CodeLimits(visitedInstructions = 3)).findings)
        assertTrue(scan(CodeLimits(classes = 1, xrefInstructions = 2)).notes.isNotEmpty())
    }
    private fun writeBehaviourClasses(root: File) {
        fun write(name: String, body: String) = File(root, "$name.smali").writeText(body.trimIndent())
        write("TrustAll", """
            .class public Lsample/TrustAll;
            .super Ljava/lang/Object;
            .implements Ljavax/net/ssl/X509TrustManager;
            .method public checkServerTrusted([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V
                .registers 3
                return-void
            .end method
        """)
        write("TrustSome", """
            .class public Lsample/TrustSome;
            .super Ljava/lang/Object;
            .implements Ljavax/net/ssl/X509TrustManager;
            .method public checkServerTrusted([Ljava/security/cert/X509Certificate;Ljava/lang/String;)V
                .registers 3
                new-instance v0, Ljava/security/cert/CertificateException;
                invoke-direct {v0}, Ljava/security/cert/CertificateException;-><init>()V
                throw v0
            .end method
        """)
        write("AnyHost", """
            .class public Lsample/AnyHost;
            .super Ljava/lang/Object;
            .implements Ljavax/net/ssl/HostnameVerifier;
            .method public verify(Ljava/lang/String;Ljavax/net/ssl/SSLSession;)Z
                .registers 4
                const/4 v0, 0x1
                return v0
            .end method
        """)
        write("Crypto", """
            .class public Lsample/Crypto;
            .super Ljava/lang/Object;
            .method public static weak()V
                .registers 1
                const-string v0, "AES"
                invoke-static {v0}, Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;
                return-void
            .end method
            .method public static fine()V
                .registers 1
                const-string v0, "AES"
                const-string v0, "AES/GCM/NoPadding"
                invoke-static {v0}, Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;
                const-string v0, "RSA/ECB/PKCS1Padding"
                invoke-static {v0}, Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;
                return-void
            .end method
        """)
        write("Web", """
            .class public Lsample/Web;
            .super Ljava/lang/Object;
            .method public static open(Landroid/webkit/WebView;Landroid/webkit/SslErrorHandler;)V
                .registers 4
                const/4 v0, 0x0
                const-string v1, "Android"
                invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V
                invoke-virtual {p1}, Landroid/webkit/SslErrorHandler;->proceed()V
                return-void
            .end method
        """)
        write("Root", """
            .class public Lother/Root;
            .super Ljava/lang/Object;
            .method public static check()Z
                .registers 1
                const-string v0, "/system/xbin/su"
                const/4 v0, 0x0
                return v0
            .end method
        """)
    }
    @Test fun indexesComponentsAndTheAppNamespaceFirstAcrossDexFiles() {
        val root = Files.createTempDirectory("dex-priority").toFile()
        try {
            fun dex(name: String, vararg types: String): ByteArray {
                val dir = File(root, name).apply { mkdirs() }
                types.forEachIndexed { i, t -> File(dir, "C$i.smali").writeText(".class public $t\n.super Ljava/lang/Object;\n") }
                val out = File(root, "$name.dex")
                assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = out.absolutePath; apiLevel = 26 }, dir.listFiles()!!.map { it.absolutePath }))
                return out.readBytes()
            }
            val apk = File(root, "multi.apk")
            ZipOutputStream(apk.outputStream()).use { zip ->
                // Libraries fill classes.dex; the app's code and its activity sit in classes2.dex.
                zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dex("one", "Landroidx/core/A;", "Landroidx/core/B;", "Lokio/C;")); zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes2.dex")); zip.write(dex("two", "Lorg/shop/ui/Main;", "Lorg/shop/data/Repo;", "Lcoil/D;")); zip.closeEntry()
            }
            val result = DexCodeScanner(CodeLimits(classes = 2)).scan(listOf(apk), "org.shop.app", entryClasses = listOf("org.shop.ui.Main"))
            assertEquals(listOf("Lorg/shop/ui/Main;", "Lorg/shop/data/Repo;"), result.classes.map { it.type })
            assertEquals(listOf("Lorg/shop/app/", "Lorg/shop/"), preferredPrefixes("org.shop.app", listOf("org.shop.ui.Main")))
            // Without components in that namespace, only the package name itself is preferred.
            assertEquals(listOf("Lcom/google/maps/"), preferredPrefixes("com.google.maps", listOf("com.other.X")))
        } finally { root.deleteRecursively() }
    }
    private fun fixture(block: (File) -> Unit) = fixture({}, block)
    private fun fixture(extra: (File) -> Unit, block: (File) -> Unit) {
        val root = Files.createTempDirectory("dex-code-test").toFile()
        try {
            val source = File(root, "Demo.smali")
            source.writeText("""
                .class public Lsample/Demo;
                .super Ljava/lang/Object;
                .method public static start()V
                    .registers 0
                    invoke-static {}, Lsample/Demo;->download()V
                    return-void
                .end method
                .method public static download()V
                    .registers 0
                    return-void
                .end method
            """.trimIndent())
            extra(root)
            val dex = File(root, "classes.dex")
            val options = SmaliOptions().apply { outputDexFile = dex.absolutePath; apiLevel = 26 }
            assertTrue(Smali.assemble(options, root.listFiles()!!.filter { it.extension == "smali" }.map { it.absolutePath }))
            val apk = File(root, "fixture.apk")
            ZipOutputStream(apk.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("classes.dex")); zip.write(dex.readBytes()); zip.closeEntry() }
            block(apk)
        } finally { root.deleteRecursively() }
    }
}
