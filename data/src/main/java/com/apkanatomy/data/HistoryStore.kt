package com.apkanatomy.data

import com.apkanatomy.domain.AnatomyReport
import com.apkanatomy.domain.forHistory
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What the history list shows without reading the whole report. */
data class HistoryEntry(val id: String, val packageName: String, val label: String, val version: String,
    val source: String, val savedAt: Long, val bytes: Long)

/**
 * Earlier reports, slimmed with [forHistory], kept as gzip files in [dir] (app-private storage).
 * Each file starts with a small header for the list, followed by the serialized report.
 * At most [maxEntries] are kept; a newer analysis of the same package, version and source replaces the older one.
 */
class HistoryStore(private val dir: File, private val maxEntries: Int = 20, private val maxBytes: Long = 8L * 1024 * 1024) {

    @Synchronized fun list(): List<HistoryEntry> =
        files().mapNotNull { file -> header(file) ?: run { file.delete(); null } }.sortedByDescending { it.savedAt }

    /** Saves [report]; returns false when it is too large to keep. */
    @Synchronized fun save(report: AnatomyReport, now: Long): Boolean {
        dir.mkdirs()
        val slim = report.forHistory()
        val source = slim.source.removePrefix(FROM_HISTORY)
        val target = File(dir, "$now-${report.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)}$SUFFIX")
        val temp = File(dir, target.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(temp)))).use { out ->
                out.writeInt(FORMAT)
                listOf(slim.packageName, slim.label, slim.version, source).forEach { out.writeUTF(it.take(1000)) }
                out.writeLong(now)
                ObjectOutputStream(out).apply { writeObject(slim); flush() }
            }
        } catch (e: IOException) { temp.delete(); throw e }
        if (temp.length() > maxBytes) { temp.delete(); return false }
        list().filter { it.packageName == slim.packageName && it.version == slim.version && it.source == source }
            .forEach { File(dir, it.id).delete() }
        if (!temp.renameTo(target)) { temp.delete(); return false }
        list().drop(maxEntries).forEach { File(dir, it.id).delete() }
        return true
    }

    /** The saved report, with its source marked as coming from the history; null when the file is gone or unreadable. */
    @Synchronized fun load(id: String): AnatomyReport? {
        val file = File(dir, id).takeIf { it.parentFile == dir && it.name.endsWith(SUFFIX) && it.isFile } ?: return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
                if (input.readInt() != FORMAT) return null
                repeat(4) { input.readUTF() }
                input.readLong()
                val report = DomainObjectInputStream(input).readObject() as AnatomyReport
                report.copy(source = FROM_HISTORY + report.source.removePrefix(FROM_HISTORY))
            }
        } catch (_: Exception) { file.delete(); null }
    }

    @Synchronized fun delete(id: String) { File(dir, id).takeIf { it.parentFile == dir && it.name.endsWith(SUFFIX) }?.delete() }

    @Synchronized fun clear() { files().forEach { it.delete() } }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }?.toList().orEmpty()

    private fun header(file: File): HistoryEntry? = try {
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
            if (input.readInt() != FORMAT) null
            else HistoryEntry(file.name, input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), file.length())
        }
    } catch (_: IOException) { null }

    /** Only the report's own classes and the standard collections behind them may be created while reading. */
    private class DomainObjectInputStream(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            val name = desc.name.trimStart('[')
            require(name.length == 1 || ALLOWED.any { name.removePrefix("L").startsWith(it) }) { "Kelas tidak diizinkan: ${desc.name}" }
            return super.resolveClass(desc)
        }
    }

    companion object {
        /** Bumped whenever the report classes change in a way older files cannot be read with. */
        const val FORMAT = 1
        const val FROM_HISTORY = "Riwayat · "
        private const val SUFFIX = ".report.gz"
        private val ALLOWED = listOf("com.apkanatomy.domain.", "java.lang.", "java.util.", "kotlin.")
    }
}
