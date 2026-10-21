package com.apkanatomy.data

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
