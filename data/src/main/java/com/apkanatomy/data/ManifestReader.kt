package com.apkanatomy.data

import com.apkanatomy.axml.AxmlDecoder
import com.apkanatomy.axml.AxmlException
import com.apkanatomy.axml.XmlElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** A decoded manifest, or null [root]/[xml] plus a user-facing [note] explaining why not. */
internal data class DecodedManifest(val root: XmlElement?, val xml: String?, val note: String?) {
    val application: XmlElement? get() = root?.childElements("application")?.firstOrNull()
}

/** Decodes the binary AndroidManifest.xml of one APK into readable XML. */
internal object ManifestReader {
    private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024

    fun read(apk: File): DecodedManifest = try {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return failed("AndroidManifest.xml tidak ditemukan di ${apk.name}.")
            if (entry.size !in 1..MAX_MANIFEST_BYTES) return failed("Ukuran manifest di luar batas 8 MiB.")
            // The declared size can lie; cap what is actually inflated too.
            val bytes = ByteArrayOutputStream().also { out ->
                try { zip.getInputStream(entry).use { copyBounded(it, out, MAX_MANIFEST_BYTES) } }
                catch (_: IllegalArgumentException) { return failed("Ukuran manifest di luar batas 8 MiB.") }
            }.toByteArray()
            val root = AxmlDecoder.decode(bytes)
            DecodedManifest(root, root.toXmlString(), null)
        }
    } catch (e: AxmlException) {
        failed("Manifest tidak dapat didekode: ${e.message}")
    } catch (e: IOException) {
        failed("Manifest tidak dapat dibaca: ${e.message}")
    }

    private fun failed(note: String) = DecodedManifest(null, null, note)
}
