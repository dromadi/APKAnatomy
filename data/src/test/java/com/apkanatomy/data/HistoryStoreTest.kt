package com.apkanatomy.data

import com.apkanatomy.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.zip.GZIPOutputStream

class HistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun report(pkg: String = "id.contoh.app", version: String = "1.0", source: String = "File APK lokal") = AnatomyReport(
        pkg, "Contoh", version, 26, 35, source, emptyList(), listOf(AppPermission("android.permission.CAMERA", "Dangerous")),
        code = CodeIndex(
            methods = listOf(CodeMethod("Lid/A;->a()V", "Lid/A;", "a", "base.apk", "classes.dex", "public", true, listOf("const-string v0, \"x\""), 1, false)),
            strings = listOf(StringUse("base.apk:classes.dex:Lid/A;->a()V", "rahasia-tidak-tersamar")),
            packages = listOf("id.contoh"), definedClasses = 10, shortNamedClasses = 2),
        manifestXml = "<manifest/>")

    @Test fun savesAndReopensASlimReport() {
        val store = HistoryStore(temp.root)
        assertTrue(store.save(report(), now = 1_000))
        val entry = store.list().single()
        assertEquals("id.contoh.app", entry.packageName)
        assertEquals("Contoh", entry.label)
        assertEquals(1_000L, entry.savedAt)
        val loaded = store.load(entry.id)!!
        assertEquals("<manifest/>", loaded.manifestXml)
        assertEquals(listOf("android.permission.CAMERA"), loaded.permissions.map { it.name })
        assertTrue(loaded.source.startsWith(HistoryStore.FROM_HISTORY))
        // The code listing (and its unmasked string literals) is not kept; the summary counts are.
        assertTrue(loaded.code.methods.isEmpty() && loaded.code.strings.isEmpty())
        assertEquals(10, loaded.code.definedClasses)
        assertEquals(listOf(HISTORY_CODE_NOTE), loaded.code.warnings)
        assertFalse(temp.root.walk().filter { it.isFile }.any { "rahasia-tidak-tersamar" in String(java.util.zip.GZIPInputStream(it.inputStream()).readBytes(), Charsets.ISO_8859_1) })
    }

    @Test fun reopeningAndSavingAgainDoesNotStackTheHistoryPrefix() {
        val store = HistoryStore(temp.root)
        store.save(report(), 1)
        val loaded = store.load(store.list().single().id)!!
        store.save(loaded, 2)
        val entry = store.list().single()
        assertEquals("File APK lokal", entry.source)
        assertEquals(HistoryStore.FROM_HISTORY + "File APK lokal", store.load(entry.id)!!.source)
    }

    @Test fun replacesTheSameVersionAndKeepsTheNewestEntries() {
        val store = HistoryStore(temp.root, maxEntries = 3)
        store.save(report(version = "1.0"), 1)
        store.save(report(version = "1.0"), 2)
        assertEquals(listOf(2L), store.list().map { it.savedAt })
        store.save(report(version = "1.1"), 3)
        store.save(report(pkg = "id.lain"), 4)
        store.save(report(pkg = "id.ketiga"), 5)
        assertEquals(listOf(5L, 4L, 3L), store.list().map { it.savedAt })
    }

    @Test fun refusesReportsAboveTheSizeLimit() {
        val store = HistoryStore(temp.root, maxBytes = 10)
        assertFalse(store.save(report(), 1))
        assertTrue(store.list().isEmpty())
        assertTrue(temp.root.listFiles()!!.isEmpty())
    }

    @Test fun dropsUnreadableFiles() {
        val store = HistoryStore(temp.root)
        store.save(report(), 1)
        val id = store.list().single().id
        File(temp.root, "2-rusak.report.gz").writeText("bukan gzip")
        assertEquals(1, store.list().size)
        assertFalse(File(temp.root, "2-rusak.report.gz").exists())
        File(temp.root, id).writeBytes(File(temp.root, id).readBytes().copyOf(60))
        assertNull(store.load(id))
        assertTrue(store.list().isEmpty())
    }

    @Test fun refusesClassesOutsideTheReportModel() {
        val file = File(temp.root, "1-asing.report.gz")
        DataOutputStream(GZIPOutputStream(file.outputStream())).use { out ->
            out.writeInt(HistoryStore.FORMAT)
            repeat(4) { out.writeUTF("x") }
            out.writeLong(1)
            ObjectOutputStream(out).apply { writeObject(File("/")); flush() }
        }
        val store = HistoryStore(temp.root)
        assertEquals(1, store.list().size)
        assertNull(store.load("1-asing.report.gz"))
        assertFalse(file.exists())
    }

    @Test fun ignoresIdsOutsideTheHistoryFolder() {
        val outside = temp.newFile("luar.report.gz")
        val store = HistoryStore(temp.newFolder("history"))
        assertNull(store.load("../luar.report.gz"))
        store.delete("../luar.report.gz")
        assertTrue(outside.exists())
    }
}
