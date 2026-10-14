package com.apkanatomy.data

import com.apkanatomy.axml.AxmlDecoder
import com.apkanatomy.axml.AxmlException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Decodes the binary AndroidManifest.xml of one APK into readable XML. */
internal object ManifestReader {
    private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024

    /** Returns the XML, or null plus a user-facing reason. */
    fun read(apk: File): Pair<String?, String?> = try {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null to "AndroidManifest.xml tidak ditemukan di ${apk.name}."
            if (entry.size !in 1..MAX_MANIFEST_BYTES) return null to "Ukuran manifest di luar batas 8 MiB."
            // The declared size can lie; cap what is actually inflated too.
            val bytes = ByteArrayOutputStream().also { out ->
                try { zip.getInputStream(entry).use { copyBounded(it, out, MAX_MANIFEST_BYTES) } }
                catch (_: IllegalArgumentException) { return null to "Ukuran manifest di luar batas 8 MiB." }
            }.toByteArray()
            AxmlDecoder.decode(bytes).toXmlString() to null
        }
    } catch (e: AxmlException) {
        null to "Manifest tidak dapat didekode: ${e.message}"
    } catch (e: IOException) {
        null to "Manifest tidak dapat dibaca: ${e.message}"
    }
}
