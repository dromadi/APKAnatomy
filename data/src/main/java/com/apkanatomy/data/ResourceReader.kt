package com.apkanatomy.data

import com.apkanatomy.axml.AxmlDecoder
import com.apkanatomy.axml.AxmlException
import com.apkanatomy.axml.ResourceTable
import com.apkanatomy.axml.XmlElement
import com.apkanatomy.axml.XmlText
import com.apkanatomy.domain.NetworkSecurityConfig
import com.apkanatomy.domain.NscScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Reads resources.arsc of one APK; a missing table is normal (resource-less APK), not an error. */
internal object ResourceReader {
    private const val MAX_TABLE_BYTES = 32L * 1024 * 1024
    private const val MAX_XML_BYTES = 1L * 1024 * 1024
    private const val NETWORK_SECURITY_CONFIG_ATTR = 0x01010527 // verified with aapt2

    /** The table, or null with a user-facing note when it exists but cannot be read. */
    fun read(apk: File): Pair<ResourceTable?, String?> = try {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("resources.arsc") ?: return null to null
            val bytes = bounded(zip, entry, MAX_TABLE_BYTES) ?: return null to "resources.arsc di luar batas 32 MiB; nama resource tidak ditampilkan."
            ResourceTable.decode(bytes) to null
        }
    } catch (e: AxmlException) {
        null to "resources.arsc tidak dapat dibaca: ${e.message}"
    } catch (e: IOException) {
        null to "resources.arsc tidak dapat dibuka: ${e.message}"
    }

    /** Locates the file behind android:networkSecurityConfig through the resource table and parses it. */
    fun networkSecurityConfig(apk: File, application: XmlElement?, table: ResourceTable?): Pair<NetworkSecurityConfig?, String?> {
        val attribute = application?.attributes?.firstOrNull { it.name == "networkSecurityConfig" || it.resourceId == NETWORK_SECURITY_CONFIG_ATTR }
            ?: return null to null
        val id = attribute.referenceId ?: return null to "Nilai networkSecurityConfig bukan referensi resource."
        val path = table?.value(id) ?: return null to "Lokasi file Network Security Config tidak dapat ditentukan tanpa resources.arsc."
        return try {
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry(path) ?: return null to "File Network Security Config $path tidak ada di APK."
                val bytes = bounded(zip, entry, MAX_XML_BYTES) ?: return null to "File Network Security Config melebihi 1 MiB."
                parseNetworkSecurityConfig(AxmlDecoder.decode(bytes, table), path) to null
            }
        } catch (e: AxmlException) {
            null to "Network Security Config tidak dapat didekode: ${e.message}"
        } catch (e: IOException) {
            null to "Network Security Config tidak dapat dibaca: ${e.message}"
        }
    }

    private fun bounded(zip: ZipFile, entry: java.util.zip.ZipEntry, limit: Long): ByteArray? {
        if (entry.size !in 1..limit) return null
        // The declared size can lie; cap what is actually inflated too.
        return try { ByteArrayOutputStream().also { out -> zip.getInputStream(entry).use { copyBounded(it, out, limit) } }.toByteArray() }
        catch (_: IllegalArgumentException) { null }
    }
}

internal fun parseNetworkSecurityConfig(root: XmlElement, file: String): NetworkSecurityConfig {
    fun attr(e: XmlElement, name: String) = e.attribute(name, namespaceUri = null)
    fun scope(e: XmlElement): NscScope {
        val pins = e.childElements("pin-set").firstOrNull()
        return NscScope(
            domains = e.childElements("domain").map { d ->
                val name = d.children.filterIsInstance<XmlText>().joinToString("") { it.text }.trim()
                if (attr(d, "includeSubdomains") == "true") "*.$name" else name
            },
            cleartextPermitted = attr(e, "cleartextTrafficPermitted")?.toBooleanStrictOrNull(),
            trustAnchors = e.childElements("trust-anchors").firstOrNull()?.childElements("certificates")?.mapNotNull { attr(it, "src") },
            pins = pins?.childElements("pin")?.size ?: 0,
            pinExpiration = pins?.let { attr(it, "expiration") },
            certificateTransparency = e.childElements("certificateTransparency").firstOrNull()?.let { attr(it, "enabled")?.toBooleanStrictOrNull() },
        )
    }
    // domain-config may nest; each level is reported as its own scope.
    fun domainScopes(e: XmlElement): List<NscScope> = e.childElements("domain-config").flatMap { listOf(scope(it)) + domainScopes(it) }
    return NetworkSecurityConfig(file, root.childElements("base-config").firstOrNull()?.let(::scope), domainScopes(root),
        root.childElements("debug-overrides").firstOrNull()?.let(::scope))
}
