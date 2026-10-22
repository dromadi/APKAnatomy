package com.apkanatomy.data

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PseudoJavaTest {
    private fun render(body: String, method: String): List<String> {
        val root = Files.createTempDirectory("pseudo").toFile()
        try {
            File(root, "A.smali").writeText(body.trimIndent())
            val dex = File(root, "classes.dex")
            assertTrue(Smali.assemble(SmaliOptions().apply { outputDexFile = dex.absolutePath; apiLevel = 26 }, listOf(File(root, "A.smali").absolutePath)))
            val file = DexFileFactory.loadDexFile(dex, Opcodes.forApi(26))
            return PseudoJava.render(file.classes.single().methods.single { it.name == method })
        } finally { root.deleteRecursively() }
    }

    @Test fun readsLikeJava() {
        val lines = render("""
            .class public Lsample/Net;
            .super Ljava/lang/Object;
            .field private client:Lokhttp3/OkHttpClient;
            .method public fetch(Ljava/lang/String;J)I
                .registers 8
                const-string v0, "https://api.contoh.id/"
                new-instance v1, Ljava/lang/StringBuilder;
                invoke-direct {v1, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
                invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                move-result-object v0
                iget-object v2, p0, Lsample/Net;->client:Lokhttp3/OkHttpClient;
                if-eqz v2, :none
                invoke-static {v0, p2, p3}, Lsample/Net;->send(Ljava/lang/String;J)I
                move-result v3
                add-int/lit8 v3, v3, 0x1
                return v3
                :none
                const/4 v3, -0x1
                return v3
            .end method
            .method public static send(Ljava/lang/String;J)I
                .registers 3
                const/4 v0, 0x0
                return v0
            .end method
        """, "fetch")
        assertEquals(listOf(
            "v0 = \"https://api.contoh.id/\";",
            "v1 = new StringBuilder(v0);",
            "v1.append(p1);",
            "v0 = v1.toString();",
            "v2 = this.client;",
            "if (v2 == 0) goto L_19;",
            // p2 is the long; its second register is skipped.
            "v3 = Net.send(v0, p2);",
            "v3 = v3 + 1;",
            "return v3;",
            "L_19:",
            "v3 = -1;",
            "return v3;",
        ), lines)
    }

    @Test fun arraysCastsAndUnusedResults() {
        val lines = render("""
            .class public Lsample/Shell;
            .super Ljava/lang/Object;
            .method public static run(Ljava/lang/Object;)V
                .registers 4
                const-string v0, "ls"
                const-string v1, "-l"
                filled-new-array {v0, v1}, [Ljava/lang/String;
                move-result-object v2
                invoke-static {}, Ljava/lang/Runtime;->getRuntime()Ljava/lang/Runtime;
                move-result-object v0
                invoke-virtual {v0, v2}, Ljava/lang/Runtime;->exec([Ljava/lang/String;)Ljava/lang/Process;
                check-cast p0, Ljava/util/Map${'$'}Entry;
                return-void
            .end method
        """, "run")
        assertEquals(listOf("v0 = \"ls\";", "v1 = \"-l\";", "v2 = new String[] { v0, v1 };", "v0 = Runtime.getRuntime();",
            "v0.exec(v2);", "p0 = (Map.Entry) p0;", "return;"), lines)
        assertEquals("int[][]", PseudoJava.simple("[[I"))
        assertEquals("Main\$\$Lambda", PseudoJava.simple("Lcom/x/Main\$\$Lambda;"))
    }
}
