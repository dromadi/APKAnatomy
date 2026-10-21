package com.apkanatomy.data

import com.apkanatomy.axml.AxmlDecoder
import com.apkanatomy.axml.AxmlException
import com.apkanatomy.axml.ResourceTable
import com.apkanatomy.domain.FilePreview
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Reads one archive entry for display; every read is bounded and nothing is executed. */
internal object PreviewReader {
    const val MAX_TEXT_BYTES = 256 * 1024
    const val MAX_XML_BYTES = 4 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val HEX_BYTES = 512

    fun preview(archive: File, path: String, resources: ResourceTable?): FilePreview = try {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(path) ?: return FilePreview.Unavailable("File tidak ditemukan di arsip.")
            if (entry.isDirectory) return FilePreview.Unavailable("Ini folder.")
            // Read just enough to classify, then at most the largest limit that could apply.
            val limit = maxOf(MAX_XML_BYTES, MAX_IMAGE_BYTES)
            val out = ByteArrayOutputStream()
            val complete = try { zip.getInputStream(entry).use { copyBounded(it, out, limit.toLong()) }; true }
                catch (_: IllegalArgumentException) { false }
            classify(out.toByteArray(), complete, resources)
        }
    } catch (e: IOException) {
        FilePreview.Unavailable("Arsip tidak dapat dibaca: ${e.message}")
    }

    /** [complete] is false when the entry was larger than what was read. */
    internal fun classify(bytes: ByteArray, complete: Boolean, resources: ResourceTable?): FilePreview {
        if (isBinaryXml(bytes)) {
            if (!complete) return hex(bytes, "XML biner lebih dari 4 MiB; hanya awal file yang ditampilkan.")
            return try {
                FilePreview.Text(AxmlDecoder.decode(bytes, resources).toXmlString(), "XML biner (didekode)", truncated = false)
            } catch (e: AxmlException) {
                hex(bytes, "XML biner tidak dapat didekode: ${e.message}")
            }
        }
        imageFormat(bytes)?.let { format ->
            return if (complete) FilePreview.Image(bytes, format) else hex(bytes, "Gambar $format lebih dari 8 MiB; tidak dipratinjau.")
        }
        text(bytes)?.let { return it }
        return hex(bytes, (knownBinary(bytes)?.let { "$it " } ?: "Format biner; ") + "ditampilkan ${minOf(bytes.size, HEX_BYTES)} byte pertama.")
    }

    private fun isBinaryXml(b: ByteArray) = b.size >= 8 && b[0] == 0x03.toByte() && b[1] == 0.toByte() && b[2] == 0x08.toByte() && b[3] == 0.toByte()

    private fun imageFormat(b: ByteArray): String? = when {
        b.startsWith(0x89, 0x50, 0x4e, 0x47) -> "PNG"
        b.startsWith(0xff, 0xd8, 0xff) -> "JPEG"
        b.startsWith(0x47, 0x49, 0x46, 0x38) -> "GIF"
        b.size >= 12 && b.startsWith(0x52, 0x49, 0x46, 0x46) && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "WebP"
        b.startsWith(0x42, 0x4d) && b.size > 54 -> "BMP"
        else -> null
    }

    /** Names well-known binary formats by their magic number. */
    private fun knownBinary(b: ByteArray): String? = when {
        b.startsWith(0xca, 0xfe, 0xba, 0xbe) -> "File class Java (bytecode JVM, bukan DEX; sering sisa dari library)."
        b.startsWith(0x64, 0x65, 0x78, 0x0a) -> "File DEX (bytecode Android); jelajahi lewat tab Kode."
        b.startsWith(0x7f, 0x45, 0x4c, 0x46) -> "Library ELF (kode mesin native)."
        b.startsWith(0x50, 0x4b, 0x03, 0x04) -> "Arsip ZIP di dalam APK (mis. JAR atau bundle)."
        b.startsWith(0x02, 0x00, 0x0c, 0x00) -> "Tabel resource biner (resources.arsc); isinya tampil di tab Manifest dan Kulit."
        b.startsWith(0x1f, 0x8b) -> "Data terkompresi gzip."
        b.startsWith(0x53, 0x51, 0x4c, 0x69, 0x74, 0x65) -> "Database SQLite."
        b.startsWith(0x54, 0x46, 0x4c, 0x33) || (b.size >= 8 && String(b, 4, 4, Charsets.US_ASCII) == "TFL3") -> "Model TensorFlow Lite."
        else -> null
    }

    private fun ByteArray.startsWith(vararg magic: Int) = size >= magic.size && magic.indices.all { this[it] == magic[it].toByte() }

    /** Strict UTF-8 without NUL bytes; a code point cut by the size limit is dropped, not rejected. */
    private fun text(bytes: ByteArray): FilePreview.Text? {
        val shown = bytes.copyOf(minOf(bytes.size, MAX_TEXT_BYTES))
        if (shown.any { it == 0.toByte() }) return null
        for (trim in 0..3) {
            if (trim > 0 && shown.size <= MAX_TEXT_BYTES - 1) break // only a cut at the limit can split a code point
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                val text = decoder.decode(ByteBuffer.wrap(shown, 0, shown.size - trim)).toString()
                val control = text.count { it < ' ' && it != '\n' && it != '\r' && it != '\t' }
                if (control * 100 > text.length.coerceAtLeast(1) * 2) return null
                return FilePreview.Text(text, "Teks", truncated = bytes.size > MAX_TEXT_BYTES)
            } catch (_: CharacterCodingException) { }
        }
        return null
    }

    private fun hex(bytes: ByteArray, note: String): FilePreview.Hex = FilePreview.Hex(buildString {
        bytes.copyOf(minOf(bytes.size, HEX_BYTES)).toList().chunked(16).forEachIndexed { row, line ->
            append("%04x  ".format(row * 16))
            append(line.joinToString(" ") { "%02x".format(it) }.padEnd(47))
            append("  ").append(line.joinToString("") { b -> (b.toInt() and 0xff).let { if (it in 0x20..0x7e) it.toChar().toString() else "." } })
            append('\n')
        }
    }.trimEnd(), note)
}
