package com.apkanatomy.data

import com.apkanatomy.domain.NativeLibInfo
import com.apkanatomy.domain.NativeReport
import com.apkanatomy.domain.demangleJni
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/** Where each entry's data starts in the archive, and whether it is stored uncompressed. */
internal object ZipLayout {
    class Entry(val stored: Boolean, val dataOffset: Long)

    fun read(file: File): Map<String, Entry> = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        val tail = ByteArray(minOf(length, 22L + 0xffff).toInt()).also { raf.seek(length - it.size); raf.readFully(it) }
        val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { t.getInt(it) == 0x06054b50 } ?: return emptyMap()
        val count = t.getShort(eocd + 10).toInt() and 0xffff
        val cdSize = t.getInt(eocd + 12).toLong() and 0xffffffffL
        val cdOffset = t.getInt(eocd + 16).toLong() and 0xffffffffL
        if (cdOffset + cdSize > length || cdSize > 64L * 1024 * 1024) return emptyMap()
        val cd = ByteArray(cdSize.toInt()).also { raf.seek(cdOffset); raf.readFully(it) }
        val c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
        val result = HashMap<String, Entry>()
        var p = 0
        val local = ByteArray(30)
        repeat(count) {
            if (p + 46 > cd.size || c.getInt(p) != 0x02014b50) return result
            val method = c.getShort(p + 10).toInt() and 0xffff
            val nameLen = c.getShort(p + 28).toInt() and 0xffff
            val extraLen = c.getShort(p + 30).toInt() and 0xffff
            val commentLen = c.getShort(p + 32).toInt() and 0xffff
            val lho = c.getInt(p + 42).toLong() and 0xffffffffL
            if (p + 46 + nameLen > cd.size) return result
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            if (lho + 30 <= length) {
                raf.seek(lho); raf.readFully(local)
                val l = ByteBuffer.wrap(local).order(ByteOrder.LITTLE_ENDIAN)
                if (l.getInt(0) == 0x04034b50) {
                    val offset = lho + 30 + (l.getShort(26).toInt() and 0xffff) + (l.getShort(28).toInt() and 0xffff)
                    result[name] = Entry(method == 0, offset)
                }
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        result
    }
}

/** Inspects every native library (lib/<abi>/<name>.so) in the given archives (64 MiB per library, 512 MiB in total). */
internal class NativeScanner(private val maxLibBytes: Long = 64L * 1024 * 1024, private val maxTotalBytes: Long = 512L * 1024 * 1024) {
    fun scan(archives: Map<String, File>, extractNativeLibs: Boolean?, checkCancelled: () -> Unit = {}): NativeReport {
        val libs = mutableListOf<NativeLibInfo>(); val warnings = mutableListOf<String>()
        var total = 0L
        for ((label, file) in archives) {
            val layout = try { ZipLayout.read(file) } catch (_: Exception) { emptyMap() }
            try {
                ZipFile(file).use { zip ->
                    for (entry in zip.entries()) {
                        checkCancelled()
                        val parts = entry.name.split('/')
                        if (parts.size != 3 || parts[0] != "lib" || !parts[2].endsWith(".so")) continue
                        val placement = layout[entry.name]
                        fun info(note: String?, elf: ElfReader.Elf? = null) = NativeLibInfo(label, entry.name, parts[1], entry.size,
                            elf?.machine, elf?.bits, elf?.loadAligns?.minOrNull(), placement?.stored ?: (entry.method == ZipEntryMethodStored),
                            placement?.dataOffset, elf?.soname, elf?.needed.orEmpty(),
                            elf?.exports.orEmpty().mapNotNull(::demangleJni).sorted(), elf?.exports?.contains("JNI_OnLoad") == true,
                            elf?.hasSymbolTable == true, elf?.hasDebugInfo == true, note)
                        if (entry.size !in 1..maxLibBytes || total + entry.size > maxTotalBytes) { libs += info("Terlalu besar untuk dibaca (batas ${maxLibBytes / (1024 * 1024)} MiB per library)."); continue }
                        val bytes = try {
                            ByteArrayOutputStream().also { out -> zip.getInputStream(entry).use { copyBounded(it, out, maxLibBytes) } }.toByteArray()
                        } catch (_: IllegalArgumentException) { libs += info("Ukuran sebenarnya melebihi batas."); continue }
                        total += bytes.size
                        libs += try { info(null, ElfReader.parse(bytes)) } catch (e: ElfReader.ElfException) { info(e.message) }
                    }
                }
            } catch (_: Exception) { warnings += "Arsip $label tidak dapat dibaca untuk analisis library native." }
        }
        return NativeReport(libs.sortedWith(compareBy({ it.abi }, { it.path }, { it.archive })), extractNativeLibs, warnings)
    }

    private companion object { const val ZipEntryMethodStored = java.util.zip.ZipEntry.STORED }
}
