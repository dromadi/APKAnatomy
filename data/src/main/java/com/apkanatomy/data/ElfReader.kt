package com.apkanatomy.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the parts of an ELF shared library that matter for studying an APK: architecture,
 * LOAD segment alignment, dynamic dependencies and exported JNI symbols. Little-endian only
 * (every Android ABI). Every offset is checked against the buffer.
 */
internal object ElfReader {
    class ElfException(message: String) : Exception(message)
    class Elf(val bits: Int, val machine: String, val loadAligns: List<Long>, val soname: String?, val needed: List<String>,
        val exports: List<String>, val hasSymbolTable: Boolean, val hasDebugInfo: Boolean,
        /** Undefined dynamic symbols: functions the library calls in other libraries (libc, libdl, …). */
        val imports: List<String> = emptyList())

    private val MACHINES = mapOf(3 to "x86", 40 to "ARM", 62 to "x86-64", 183 to "AArch64", 243 to "RISC-V")
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val SHT_SYMTAB = 2
    private const val SHT_DYNSYM = 11

    fun parse(bytes: ByteArray): Elf = try { read(bytes) } catch (e: IndexOutOfBoundsException) {
        throw ElfException("ELF terpotong atau rusak.")
    }

    private fun read(bytes: ByteArray): Elf {
        if (bytes.size < 52 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte())
            throw ElfException("Bukan file ELF.")
        val bits = when (bytes[4].toInt()) { 1 -> 32; 2 -> 64; else -> throw ElfException("Kelas ELF tidak dikenal.") }
        if (bytes[5].toInt() != 1) throw ElfException("ELF big-endian tidak didukung.")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val is64 = bits == 64
        fun word(at: Int): Long = if (is64) b.getLong(at) else b.getInt(at).toLong() and 0xffffffffL
        fun u16(at: Int) = b.getShort(at).toInt() and 0xffff
        fun u32(at: Int) = b.getInt(at).toLong() and 0xffffffffL
        fun index(value: Long): Int = if (value in 0..Int.MAX_VALUE) value.toInt() else throw ElfException("Offset ELF di luar file.")

        val machine = MACHINES[u16(18)] ?: "mesin ${u16(18)}"
        val phoff = index(word(if (is64) 32 else 28)); val shoff = index(word(if (is64) 40 else 32))
        val phentsize = u16(if (is64) 54 else 42); val phnum = u16(if (is64) 56 else 44)
        val shentsize = u16(if (is64) 58 else 46); val shnum = u16(if (is64) 60 else 48); val shstrndx = u16(if (is64) 62 else 50)

        class Segment(val type: Long, val offset: Long, val vaddr: Long, val filesz: Long, val align: Long)
        val segments = (0 until phnum).map { i ->
            val p = phoff + i * phentsize
            if (is64) Segment(u32(p), b.getLong(p + 8), b.getLong(p + 16), b.getLong(p + 32), b.getLong(p + 48))
            else Segment(u32(p), u32(p + 4), u32(p + 8), u32(p + 16), u32(p + 28))
        }
        fun fileOffset(vaddr: Long): Int? = segments.firstOrNull { it.type == PT_LOAD.toLong() && vaddr >= it.vaddr && vaddr < it.vaddr + it.filesz }
            ?.let { index(vaddr - it.vaddr + it.offset) }
        fun cString(at: Int): String { var end = at; while (bytes[end] != 0.toByte()) end++; return String(bytes, at, end - at, Charsets.UTF_8) }

        // Dynamic section: DT_NEEDED and DT_SONAME are offsets into DT_STRTAB.
        val needed = mutableListOf<String>(); var soname: String? = null
        segments.firstOrNull { it.type == PT_DYNAMIC.toLong() }?.let { dyn ->
            val entry = if (is64) 16 else 8
            val tags = (0 until (dyn.filesz / entry).coerceAtMost(4096).toInt()).map { i ->
                val at = index(dyn.offset) + i * entry
                word(at) to word(at + entry / 2)
            }.takeWhile { it.first != 0L }
            val strtab = tags.firstOrNull { it.first == 5L }?.second?.let(::fileOffset)
            if (strtab != null) {
                tags.filter { it.first == 1L }.forEach { needed += cString(strtab + index(it.second)) }
                tags.firstOrNull { it.first == 14L }?.let { soname = cString(strtab + index(it.second)) }
            }
        }

        // Section headers are optional at runtime; when present they give .dynsym, .symtab and debug sections.
        var hasSymtab = false; var hasDebug = false; val exports = mutableListOf<String>(); val imports = mutableListOf<String>()
        if (shoff != 0 && shnum in 1..65_535) {
            class Section(val nameOffset: Int, val type: Long, val offset: Long, val size: Long, val link: Int)
            val sections = (0 until shnum).map { i ->
                val s = shoff + i * shentsize
                if (is64) Section(b.getInt(s), u32(s + 4), b.getLong(s + 24), b.getLong(s + 32), b.getInt(s + 40))
                else Section(b.getInt(s), u32(s + 4), u32(s + 16), u32(s + 20), b.getInt(s + 24))
            }
            val names = sections.getOrNull(shstrndx)?.let { index(it.offset) }
            hasSymtab = sections.any { it.type == SHT_SYMTAB.toLong() }
            hasDebug = names != null && sections.any { cString(names + it.nameOffset).startsWith(".debug_") }
            sections.firstOrNull { it.type == SHT_DYNSYM.toLong() }?.let { dynsym ->
                val strtab = sections.getOrNull(dynsym.link)?.let { index(it.offset) } ?: return@let
                val entry = if (is64) 24 else 16
                for (i in 0 until (dynsym.size / entry).coerceAtMost(200_000).toInt()) {
                    val at = index(dynsym.offset) + i * entry
                    val info = bytes[at + if (is64) 4 else 12].toInt() and 0xff
                    val shndx = u16(at + if (is64) 6 else 14)
                    val binding = info ushr 4
                    if (binding != 1 && binding != 2) continue
                    val name = cString(strtab + b.getInt(at))
                    if (shndx == 0) { if (name.isNotEmpty() && imports.size < 20_000) imports += name; continue }
                    if (name.startsWith("Java_") || name == "JNI_OnLoad") exports += name
                }
            }
        }
        return Elf(bits, machine, segments.filter { it.type == PT_LOAD.toLong() }.map { it.align }, soname, needed, exports, hasSymtab, hasDebug, imports)
    }
}
