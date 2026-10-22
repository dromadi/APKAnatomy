package com.apkanatomy.data

import com.apkanatomy.domain.AppAudit
import com.apkanatomy.domain.AuditSnapshot
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The last finished audit, kept as one gzip file in [dir]. Fields are written one by one (no Java
 * serialization), so reading never creates objects from the file and a new APKAnatomy version can still read it.
 */
class AuditStore(private val dir: File) {
    private val file get() = File(dir, "last$SUFFIX")

    @Synchronized fun load(): AuditSnapshot? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
                if (input.readInt() != FORMAT) return null
                val savedAt = input.readLong()
                val includeSystem = input.readBoolean()
                val apps = List(input.readInt().also { require(it in 0..MAX_APPS) }) { input.readApp() }
                AuditSnapshot(savedAt, includeSystem, apps)
            }
        } catch (_: Exception) { file.delete(); null }
    }

    @Synchronized fun save(snapshot: AuditSnapshot) {
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        try {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(temp)))).use { out ->
                out.writeInt(FORMAT)
                out.writeLong(snapshot.savedAt)
                out.writeBoolean(snapshot.includeSystem)
                val apps = snapshot.apps.take(MAX_APPS)
                out.writeInt(apps.size)
                apps.forEach { out.writeApp(it) }
            }
        } catch (e: IOException) { temp.delete(); throw e }
        if (!temp.renameTo(file)) { temp.delete(); throw IOException("Audit gagal disimpan") }
    }

    @Synchronized fun clear() { file.delete() }

    private fun DataOutputStream.writeApp(a: AppAudit) {
        listOf(a.packageName, a.label, a.versionName).forEach { writeText(it) }
        writeBoolean(a.system); writeInt(a.targetSdk)
        writeBoolean(a.debuggable); writeBoolean(a.allowBackup); writeBoolean(a.cleartextTraffic)
        writeOptional(a.installer)
        writeList(a.permissions) { writeText(it) }
        writeInt(a.openComponents)
        writeList(a.trackers) { writeText(it.first); writeText(it.second) }
        writeOptional(a.note)
    }

    private fun DataInputStream.readApp(): AppAudit {
        val (packageName, label, versionName) = List(3) { readUTF() }
        val system = readBoolean(); val targetSdk = readInt()
        val debuggable = readBoolean(); val allowBackup = readBoolean(); val cleartext = readBoolean()
        val installer = readOptional()
        val permissions = readList { readUTF() }
        val open = readInt()
        val trackers = readList { readUTF() to readUTF() }
        return AppAudit(packageName, label, system, versionName, targetSdk, debuggable, allowBackup, cleartext, installer, permissions, open, trackers, readOptional())
    }

    private fun DataOutputStream.writeText(s: String) = writeUTF(s.take(1000))
    private fun DataOutputStream.writeOptional(s: String?) { writeBoolean(s != null); if (s != null) writeText(s) }
    private fun DataInputStream.readOptional(): String? = if (readBoolean()) readUTF() else null
    private fun <T> DataOutputStream.writeList(list: List<T>, write: (T) -> Unit) { val l = list.take(MAX_LIST); writeInt(l.size); l.forEach(write) }
    private fun <T> DataInputStream.readList(read: () -> T): List<T> = List(readInt().also { require(it in 0..MAX_LIST) }) { read() }

    private companion object {
        const val FORMAT = 0x41554431 // "AUD1"
        const val SUFFIX = ".audit.gz"
        const val MAX_APPS = 5000
        const val MAX_LIST = 2000
    }
}
