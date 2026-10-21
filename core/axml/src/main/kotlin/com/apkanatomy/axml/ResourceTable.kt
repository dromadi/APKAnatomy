package com.apkanatomy.axml

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A decoded resources.arsc: resource names and values per configuration.
 *
 * Format reference: `ResourceTypes.h` in AOSP (ResTable_header, ResTable_package, ResTable_type,
 * ResTable_entry and its compact form, ResTable_config). Supports 32-bit, 16-bit (FLAG_OFFSET16)
 * and sparse (FLAG_SPARSE) entry offsets.
 */
class ResourceTable private constructor(
    val packages: List<Package>,
    private val entries: Map<Int, List<Entry>>,
) {
    class Package(val id: Int, val name: String)

    /** One value of a resource in one configuration; [config] is "" for the default configuration. */
    class Entry(val config: String, val isDefault: Boolean, val name: String, internal val dataType: Int?, internal val data: Int, internal val bagSize: Int)

    private lateinit var globalStrings: List<String>

    val resourceCount: Int get() = entries.size

    /** "type/name", prefixed with the package name for packages other than the app's own (0x7f). */
    fun name(id: Int): String? {
        val entry = entries[id]?.firstOrNull() ?: return null
        val pkg = packages.firstOrNull { it.id == id ushr 24 }
        return if (pkg == null || pkg.id == 0x7f) entry.name else "${pkg.name}:${entry.name}"
    }

    /** The default-configuration value (or the first one), formatted like source XML. File resources yield their path. */
    fun value(id: Int): String? = entries[id]?.let { list -> (list.firstOrNull { it.isDefault } ?: list.first()).format() }

    /** Every configuration's value, e.g. ("", "Hello") and ("in", "Halo"). */
    fun values(id: Int): List<Pair<String, String>> = entries[id].orEmpty().map { it.config to it.format() }

    /** Resource IDs per type name, e.g. "string" to 120. */
    fun typeCounts(): Map<String, Int> = entries.values.groupingBy { it.first().name.substringBefore('/') }.eachCount()

    /** Language/region qualifiers that have at least one value, e.g. "in", "en-rGB". */
    fun locales(): Set<String> = entries.values.asSequence().flatten()
        .mapNotNull { e -> e.config.split('-').takeWhile { it.length == 2 && it.all(Char::isLetter) || it.startsWith("r") && it.length == 3 }.joinToString("-").ifEmpty { null } }
        .toSortedSet()

    private fun Entry.format(): String = when {
        dataType == null -> "(bag, $bagSize item)"
        else -> formatTypedValue(dataType, data, { globalStrings.getOrNull(it) }, ::name)
    }

    companion object {
        private const val RES_TABLE_TYPE = 0x0002
        private const val RES_TABLE_PACKAGE_TYPE = 0x0200
        private const val RES_TABLE_TYPE_TYPE = 0x0201
        private const val FLAG_SPARSE = 0x01
        private const val FLAG_OFFSET16 = 0x02
        private const val ENTRY_FLAG_COMPLEX = 0x0001
        private const val ENTRY_FLAG_COMPACT = 0x0008
        private const val MAX_ENTRIES = 2_000_000

        /** @throws AxmlException if [bytes] is not a well-formed resource table. */
        fun decode(bytes: ByteArray): ResourceTable = try {
            parse(bytes)
        } catch (e: AxmlException) {
            throw e
        } catch (e: RuntimeException) {
            throw AxmlException("Malformed resource table: ${e.message}", e)
        }

        private fun parse(bytes: ByteArray): ResourceTable {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 12 || buf.u16(0) != RES_TABLE_TYPE) throw AxmlException("Not a resource table")
            val end = buf.getInt(4).toLong() and 0xffffffffL
            if (end > bytes.size) throw AxmlException("Resource table truncated: header declares $end bytes, file has ${bytes.size}")
            var global: List<String> = emptyList()
            val packages = mutableListOf<Package>()
            val entries = HashMap<Int, MutableList<Entry>>()
            var total = 0

            fun chunks(from: Int, until: Long, visit: (pos: Int, type: Int, headerSize: Int, size: Int) -> Unit) {
                var pos = from
                while (pos + 8 <= until) {
                    val type = buf.u16(pos); val headerSize = buf.u16(pos + 2); val size = buf.getInt(pos + 4)
                    if (size < 8 || headerSize > size || pos.toLong() + size > until) throw AxmlException("Invalid chunk 0x%04x at offset %d".format(type, pos))
                    visit(pos, type, headerSize, size)
                    pos += size
                }
            }

            chunks(buf.u16(2), end) { pos, type, headerSize, size ->
                when (type) {
                    RES_STRING_POOL_TYPE -> global = readStringPool(buf, bytes, pos)
                    RES_TABLE_PACKAGE_TYPE -> {
                        val packageId = buf.getInt(pos + 8)
                        val name = String(bytes, pos + 12, 256, Charsets.UTF_16LE).substringBefore('\u0000')
                        val typeNames = readStringPool(buf, bytes, pos + buf.getInt(pos + 268))
                        val keyNames = readStringPool(buf, bytes, pos + buf.getInt(pos + 276))
                        val typeIdOffset = if (headerSize >= 288) buf.getInt(pos + 284) else 0
                        packages += Package(packageId, name)
                        chunks(pos + headerSize, pos.toLong() + size) { t, chunkType, h, _ ->
                            if (chunkType != RES_TABLE_TYPE_TYPE) return@chunks
                            val typeId = bytes[t + 8].toInt() and 0xff
                            val flags = bytes[t + 9].toInt() and 0xff
                            val count = buf.getInt(t + 12)
                            val entriesStart = t + buf.getInt(t + 16)
                            val config = configName(buf, bytes, t + 20)
                            val typeName = typeNames.getOrNull(typeId - 1 - typeIdOffset) ?: "type$typeId"
                            if (count < 0 || total + count > MAX_ENTRIES) throw AxmlException("Too many entries")
                            for (i in 0 until count) {
                                val (index, offset) = when {
                                    flags and FLAG_SPARSE != 0 -> buf.u16(t + h + i * 4) to buf.u16(t + h + i * 4 + 2) * 4
                                    flags and FLAG_OFFSET16 != 0 -> i to buf.u16(t + h + i * 2).let { if (it == 0xffff) -1 else it * 4 }
                                    else -> i to buf.getInt(t + h + i * 4)
                                }
                                if (offset == -1) continue
                                val e = entriesStart + offset
                                val entryFlags = buf.u16(e + 2)
                                val key: Int; val dataType: Int?; val data: Int; var bagSize = 0
                                when {
                                    entryFlags and ENTRY_FLAG_COMPACT != 0 -> { key = buf.u16(e); dataType = entryFlags ushr 8; data = buf.getInt(e + 4) }
                                    entryFlags and ENTRY_FLAG_COMPLEX != 0 -> { key = buf.getInt(e + 4); dataType = null; data = 0; bagSize = buf.getInt(e + 12) }
                                    else -> { key = buf.getInt(e + 4); val v = e + buf.u16(e); dataType = bytes[v + 3].toInt() and 0xff; data = buf.getInt(v + 4) }
                                }
                                val id = (packageId shl 24) or (typeId shl 16) or index
                                val entryName = "$typeName/${keyNames.getOrNull(key) ?: "0x%04x".format(index)}"
                                entries.getOrPut(id) { mutableListOf() } += Entry(config.first, config.second, entryName, dataType, data, bagSize)
                                total++
                            }
                        }
                    }
                }
            }
            return ResourceTable(packages, entries).also { it.globalStrings = global }
        }

        private val DENSITIES = mapOf(120 to "ldpi", 160 to "mdpi", 213 to "tvdpi", 240 to "hdpi", 320 to "xhdpi",
            480 to "xxhdpi", 640 to "xxxhdpi", 0xfffe to "anydpi", 0xffff to "nodpi")

        /** Qualifier string for the ResTable_config at [c] (common qualifiers only) and whether it is the default. */
        private fun configName(buf: ByteBuffer, bytes: ByteArray, c: Int): Pair<String, Boolean> {
            val size = buf.getInt(c)
            if (size < 28) return "" to true
            val isDefault = (4 until minOf(size, 64)).all { bytes[c + it] == 0.toByte() }
            val parts = mutableListOf<String>()
            fun letters(at: Int) = if (bytes[c + at] == 0.toByte() || bytes[c + at].toInt() and 0x80 != 0) null
                else String(bytes, c + at, 2, Charsets.US_ASCII)
            letters(8)?.let { parts += it }
            letters(10)?.let { parts += "r$it" }
            if (size >= 30 && (bytes[c + 29].toInt() and 0x30) == 0x20) parts += "night"
            buf.u16(c + 14).takeIf { it != 0 }?.let { parts += DENSITIES[it] ?: "${it}dpi" }
            buf.u16(c + 24).takeIf { it != 0 }?.let { parts += "v$it" }
            return parts.joinToString("-") to isDefault
        }
    }
}
