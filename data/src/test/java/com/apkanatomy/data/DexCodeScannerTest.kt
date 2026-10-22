package com.apkanatomy.data

import com.apkanatomy.domain.BehaviorCollector
import com.apkanatomy.domain.BehaviorPatterns
import org.junit.Assert.*
import org.junit.Test
import com.apkanatomy.domain.EdgeKind
import com.apkanatomy.domain.FlowEdge
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
    @Test fun readsConstantsForFlagAwareRules() = fixture({ root ->
        File(root, "Flags.smali").writeText("""
            .class public Lsample/Flags;
            .super Ljava/lang/Object;
            .method public static notify(Landroid/content/Context;Landroid/content/Intent;)V
                .registers 4
                const/4 v0, 0x0
                const/high16 v1, 0x2000000
                invoke-static {p0, v0, p1, v1}, Landroid/app/PendingIntent;->getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
                return-void
            .end method
            .method public static safe(Landroid/content/Context;Landroid/content/Intent;)V
                .registers 4
                const/4 v0, 0x0
                const/high16 v1, 0x4000000
                invoke-static {p0, v0, p1, v1}, Landroid/app/PendingIntent;->getActivity(Landroid/content/Context;ILandroid/content/Intent;I)Landroid/app/PendingIntent;
                return-void
            .end method
            .method public static debug()V
                .registers 1
                const/4 v0, 0x1
                invoke-static {v0}, Landroid/webkit/WebView;->setWebContentsDebuggingEnabled(Z)V
                return-void
            .end method
            .method public static key()[B
                .registers 3
                const/16 v0, 0x10
                new-array v0, v0, [B
                new-instance v1, Ljava/util/Random;
                invoke-direct {v1}, Ljava/util/Random;-><init>()V
                invoke-virtual {v1, v0}, Ljava/util/Random;->nextBytes([B)V
                new-instance v1, Ljavax/crypto/spec/IvParameterSpec;
                invoke-direct {v1, v0}, Ljavax/crypto/spec/IvParameterSpec;-><init>([B)V
                return-object v0
            .end method
            .method public static dice()I
                .registers 2
                new-instance v0, Ljava/util/Random;
                invoke-direct {v0}, Ljava/util/Random;-><init>()V
                const/4 v1, 0x6
                invoke-virtual {v0, v1}, Ljava/util/Random;->nextInt(I)I
                move-result v0
                return v0
            .end method
        """.trimIndent())
    }) { apk ->
        fun scan(limits: CodeLimits) = BehaviorCollector("sample").also { DexCodeScanner(limits).scan(listOf(apk), "sample", behaviors = it) }.result()
        val full = scan(CodeLimits())
        val byId = full.findings.associateBy { it.id }
        fun where(id: String) = byId.getValue(id).usedBy.map { it.substringAfter("classes.dex:") }
        assertEquals(listOf("Lsample/Flags;->notify(Landroid/content/Context;Landroid/content/Intent;)V"), where("pending-mutable"))
        assertEquals(listOf("Lsample/Flags;->debug()V"), where("webview-debug"))
        assertEquals(listOf("Lsample/Flags;->key()[B"), where("weak-random-crypto"))
        // The pass beyond the index reads constants the same way.
        assertEquals(full.findings, scan(CodeLimits(classes = 1)).findings)
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
    @Test fun linksComponentsStartedFromCode() = fixture({ root ->
        File(root, "Main.smali").writeText("""
            .class public Lsample/Main;
            .super Landroid/app/Activity;
            .method public open()V
                .registers 3
                new-instance v0, Landroid/content/Intent;
                const-class v1, Lsample/Second;
                invoke-direct {v0, p0, v1}, Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                invoke-virtual {p0, v0}, Lsample/Main;->startActivity(Landroid/content/Intent;)V
                return-void
            .end method
            .method public tag()Ljava/lang/String;
                .registers 2
                const-class v0, Lsample/Sync;
                invoke-virtual {v0}, Ljava/lang/Class;->getName()Ljava/lang/String;
                move-result-object v0
                return-object v0
            .end method
        """.trimIndent())
        File(root, "Lambda.smali").writeText("""
            .class final Lsample/Main${'$'}onCreate${'$'}1;
            .super Ljava/lang/Object;
            .method public run(Landroid/content/Context;)V
                .registers 4
                new-instance v0, Landroid/content/ComponentName;
                const-class v1, Lsample/Sync;
                invoke-direct {v0, p1, v1}, Landroid/content/ComponentName;-><init>(Landroid/content/Context;Ljava/lang/Class;)V
                return-void
            .end method
        """.trimIndent())
    }) { apk ->
        fun scan(limits: CodeLimits) = com.apkanatomy.domain.ScreenCollector(listOf("sample.Main", "sample.Second", "sample.Sync"))
            .also { DexCodeScanner(limits).scan(listOf(apk), "sample", screens = it) }.result()
        val links = scan(CodeLimits())
        assertEquals(setOf("Lsample/Main;" to "Lsample/Second;", "Lsample/Main${'$'}onCreate${'$'}1;" to "Lsample/Sync;"), links.map { it.from to it.to }.toSet())
        assertEquals("base.apk:classes.dex:Lsample/Main;->open()V", links.single { it.to == "Lsample/Second;" }.methodId)
        // Classes beyond the index are read by the last pass.
        assertEquals(links.toSet(), scan(CodeLimits(classes = 1)).toSet())
    }

    @Test fun readsTheControlFlowOfOneMethod() = fixture({ root ->
        File(root, "Flow.smali").writeText("""
            .class public Lsample/Flow;
            .super Ljava/lang/Object;
            .method public static run(I)I
                .registers 3
                const/4 v0, 0x0
                :loop
                if-ge v0, p0, :done
                add-int/lit8 v0, v0, 0x1
                goto :loop
                :done
                packed-switch v0, :table
                const/4 v1, 0x0
                return v1
                :one
                :try_start
                invoke-static {}, Lsample/Demo;->download()V
                :try_end
                .catch Ljava/io/IOException; {:try_start .. :try_end} :failed
                const/4 v1, 0x1
                return v1
                :failed
                move-exception v1
                throw v1
                :table
                .packed-switch 0x1
                    :one
                    :one
                .end packed-switch
            .end method
        """.trimIndent())
    }) { apk ->
        val id = "base.apk:classes.dex:Lsample/Flow;->run(I)I"
        val flow = ControlFlowReader.read(mapOf("base.apk" to apk), id)
        assertNull(flow.note)
        // entry, loop test, loop body, switch, default, try, after try, handler
        assertEquals(8, flow.blocks.size)
        val kinds = flow.blocks.map { b -> b.edges.map { it.kind } }
        assertEquals(listOf(EdgeKind.TRUE, EdgeKind.FALSE), kinds[1])
        assertTrue(flow.isBack(flow.blocks[2], flow.blocks[2].edges.single()))
        val switch = flow.blocks[3].edges
        assertEquals(listOf(FlowEdge(5, EdgeKind.CASE, "1, 2"), FlowEdge(4, EdgeKind.DEFAULT)), switch)
        assertEquals(listOf(FlowEdge(6, EdgeKind.NEXT), FlowEdge(7, EdgeKind.CATCH, "IOException")), flow.blocks[5].edges)
        assertEquals("throw", flow.blocks[7].exit)
        assertTrue(flow.blocks[3].lines.single().contains("packed-switch"))
        // if, switch with one case target: complexity 3; one loop.
        assertEquals(3, flow.complexity)
        assertEquals(1, flow.loops)
        assertNotNull(ControlFlowReader.read(mapOf("base.apk" to apk), "base.apk:classes.dex:Lsample/Nope;->x()V").note)
        assertNotNull(ControlFlowReader.read(emptyMap(), id).note)
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
