package com.apkanatomy.data

import com.apkanatomy.domain.*
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.OffsetInstruction
import org.jf.dexlib2.iface.instruction.SwitchPayload
import org.jf.dexlib2.util.ReferenceUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads one method straight from its DEX file and builds its control-flow graph. Done on demand, because the
 * code index keeps only a short instruction listing per method.
 */
internal object ControlFlowReader {
    /** [methodId] is "archive:classesN.dex:Lowner;->name(…)…" as in the code index; [archives] maps archive labels to files. */
    fun read(archives: Map<String, File>, methodId: String, maxDexBytes: Long = 64L * 1024 * 1024): ControlFlow {
        val archive = methodId.substringBefore(':')
        val dexName = methodId.substringAfter(':').substringBefore(':')
        val descriptor = methodId.substringAfter(':').substringAfter(':')
        val file = archives[archive] ?: return unavailable("Arsip tidak lagi tersedia; buka ulang laporannya dari aplikasi atau file APK.")
        val dex = cached?.takeIf { it.first == file && it.second == dexName }?.third ?: ZipFile(file).use { zip ->
            val entry = zip.getEntry(dexName)?.takeIf { it.size in 112..maxDexBytes } ?: return unavailable("$dexName tidak ditemukan atau terlalu besar.")
            val bytes = zip.getInputStream(entry).use { input -> ByteArrayOutputStream().use { out -> copyBounded(input, out, entry.size); out.toByteArray() } }
            DexBackedDexFile.fromInputStream(null, bytes.inputStream()).also { cached = Triple(file, dexName, it) }
        }
        val type = descriptor.substringBefore("->")
        val method = dex.classes.firstOrNull { it.type == type }?.methods?.firstOrNull { ReferenceUtil.getMethodDescriptor(it) == descriptor }
            ?: return unavailable("Method tidak ditemukan di $dexName.")
        val implementation = method.implementation ?: return unavailable("Method ini tidak memiliki kode DEX (abstract atau native).")
        val all = implementation.instructions.toList()
        var at = 0
        val offsets = all.map { val o = at; at += it.codeUnits; o }
        val payloads = all.indices.filter { all[it] is SwitchPayload }.associate { offsets[it] to all[it] as SwitchPayload }
        val instructions = all.indices.filter { !all[it].opcode.name.endsWith("-payload") }.map { i ->
            val ins = all[i]
            val offset = offsets[i]
            val name = ins.opcode.name
            val text = renderInstruction(offset, ins)
            when {
                name.startsWith("goto") -> FlowInstruction(offset, ins.codeUnits, text, FlowKind.GOTO, listOf(offset + (ins as OffsetInstruction).codeOffset))
                name.startsWith("if-") -> FlowInstruction(offset, ins.codeUnits, text, FlowKind.BRANCH, listOf(offset + (ins as OffsetInstruction).codeOffset))
                name == "packed-switch" || name == "sparse-switch" -> {
                    val payload = payloads[offset + (ins as OffsetInstruction).codeOffset]
                    // Case offsets in the payload are relative to the switch instruction.
                    val cases = payload?.switchElements.orEmpty()
                    FlowInstruction(offset, ins.codeUnits, text, FlowKind.SWITCH, cases.map { offset + it.offset }, cases.map { it.key })
                }
                name.startsWith("return") -> FlowInstruction(offset, ins.codeUnits, text, FlowKind.RETURN)
                name == "throw" -> FlowInstruction(offset, ins.codeUnits, text, FlowKind.THROW)
                else -> FlowInstruction(offset, ins.codeUnits, text)
            }
        }
        val tries = implementation.tryBlocks.map { t ->
            FlowTry(t.startCodeAddress, t.startCodeAddress + t.codeUnitCount, t.exceptionHandlers.map { it.exceptionType to it.handlerCodeAddress })
        }
        return controlFlow(instructions, tries)
    }

    /** The last DEX read, so opening several methods of one file parses it once; cleared with the report. */
    @Volatile private var cached: Triple<File, String, DexBackedDexFile>? = null

    fun clear() { cached = null }

    private fun unavailable(note: String) = ControlFlow(emptyList(), truncated = false, note = note)
}
