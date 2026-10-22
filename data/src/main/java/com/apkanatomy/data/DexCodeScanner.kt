package com.apkanatomy.data

import com.apkanatomy.domain.*
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.util.ReferenceUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

internal data class CodeLimits(val dexBytes: Long = 16L * 1024 * 1024,
    val totalBytes: Long = 48L * 1024 * 1024, val classes: Int = 20_000,
    val methods: Int = 30_000, val visitedInstructions: Int = 500_000,
    val displayedInstructions: Int = 30_000, val calls: Int = 40_000, val strings: Int = 50_000,
    /** Instructions read, beyond the index, only to link strings to the methods that load them. */
    val xrefInstructions: Int = 5_000_000)

@Suppress("DEPRECATION")
internal class DexCodeScanner(private val limits: CodeLimits = CodeLimits()) {
    init { require(limits.dexBytes > 0 && limits.totalBytes > 0 && limits.classes > 0 && limits.methods > 0 && limits.visitedInstructions > 0 && limits.displayedInstructions > 0 && limits.calls > 0) }
    /** @param traces receives every string of every DEX read, for endpoint and secret detection. */
    fun scan(files: List<File>, preferredPackage: String? = null, traces: TraceCollector? = null, checkCancelled: () -> Unit = {}): CodeIndex {
        val classes = mutableListOf<CodeClass>()
        val methods = mutableListOf<CodeMethod>()
        val calls = mutableListOf<MethodCall>()
        val warnings = linkedSetOf<String>()
        var totalBytes = 0L
        var visited = 0
        var displayed = 0
        // Package names are gathered from every DEX that fits the byte budget, even after the
        // class/method index is full, because SDK detection needs the whole namespace.
        val packages = sortedSetOf<String>()
        val strings = mutableListOf<StringUse>()
        var definedClasses = 0
        var shortNamed = 0
        var indexFull = false
        var xrefVisited = 0
        fun result() = CodeIndex(classes, methods, calls, warnings.toList(), packages.toList(), definedClasses, shortNamed, strings)
        for ((index, file) in files.withIndex()) {
            val archive = if (index == 0) "base.apk" else file.name
            checkCancelled()
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    var entryCount = 0
                    val seen = mutableSetOf<String>()
                    while (entries.hasMoreElements()) {
                        checkCancelled()
                        if (++entryCount > 50_000) { warnings.add("Indeks kode dibatasi pada 50.000 entri per arsip."); break }
                        val entry = entries.nextElement()
                        if (!Regex("classes([0-9]+)?\\.dex").matches(entry.name) || !seen.add(entry.name)) continue
                        if (entry.size !in 112..limits.dexBytes || entry.size > limits.totalBytes - totalBytes) {
                            warnings.add("${archive}/${entry.name} dilewati karena batas ukuran DEX; indeks hanya sebagian."); continue
                        }
                        totalBytes += entry.size
                        try {
                            val bytes = zip.getInputStream(entry).use { input ->
                                ByteArrayOutputStream().use { out -> copyBounded(input, out, entry.size); out.toByteArray() }
                            }
                            require(bytes.size.toLong() == entry.size) { "Ukuran DEX tidak cocok" }
                            val dex = DexBackedDexFile.fromInputStream(null, bytes.inputStream())
                            for (cls in dex.classes) {
                                val name = cls.type.removePrefix("L").removeSuffix(";")
                                packages.add(name.substringBeforeLast('/', "").replace('/', '.'))
                                definedClasses++
                                if (name.substringAfterLast('/').substringBefore('$').length <= 2) shortNamed++
                            }
                            var dexHasFindings = false
                            if (traces != null) dex.stringSection.forEachIndexed { i, s ->
                                if (i % 4096 == 0) checkCancelled()
                                traces.scan(s, "$archive/${entry.name}")
                                if (!dexHasFindings && traces.isInteresting(s)) dexHasFindings = true
                            }
                            val indexedTypes = HashSet<String>()
                            if (!indexFull) indexDex@ run {
                            val prefix = preferredPackage?.takeIf { it.isNotBlank() }?.replace('.', '/')?.let { "L$it/" }
                            val ordered = if (prefix == null) dex.classes.asSequence() else
                                dex.classes.asSequence().filter { it.type.startsWith(prefix) } +
                                    dex.classes.asSequence().filter { !it.type.startsWith(prefix) }
                            classLoop@ for (cls in ordered) {
                                checkCancelled()
                                if (classes.size >= limits.classes) { warnings.add("Batas class tercapai; indeks hanya sebagian."); indexFull = true; break@classLoop }
                                indexedTypes.add(cls.type)
                                classes.add(CodeClass(cls.type, cls.superclass, cls.interfaces.toList(), archive, entry.name))
                                for (method in cls.methods) {
                                    checkCancelled()
                                    if (methods.size >= limits.methods) { warnings.add("Batas method tercapai; indeks hanya sebagian."); indexFull = true; break@classLoop }
                                    val descriptor = ReferenceUtil.getMethodDescriptor(method)
                                    val id = "$archive:${entry.name}:$descriptor"
                                    val implementation = method.implementation
                                    val lines = mutableListOf<String>()
                                    var count = 0
                                    var offset = 0
                                    var truncated = false
                                    if (implementation != null) {
                                        for (instruction in implementation.instructions) {
                                            if (visited >= limits.visitedInstructions) { warnings.add("Batas pemindaian instruksi tercapai; Find usage hanya mencakup instruksi terbaca."); truncated = true; break }
                                            if (visited % 256 == 0) checkCancelled()
                                            visited++; count++
                                            if (lines.size < 120 && displayed < limits.displayedInstructions) {
                                                lines.add(renderInstruction(offset, instruction)); displayed++
                                            } else truncated = true
                                            if (instruction is ReferenceInstruction && instruction.reference is StringReference) {
                                                val literal = (instruction.reference as StringReference).string
                                                traces?.reference(literal, id)
                                                if (strings.size < limits.strings) strings.add(StringUse(id, literal.take(300)))
                                                else warnings.add("Batas ${limits.strings} string literal tercapai; pencarian string hanya sebagian.")
                                            }
                                            if (instruction.opcode.name.startsWith("invoke", ignoreCase = true) && instruction is ReferenceInstruction && instruction.reference is MethodReference) {
                                                if (calls.size < limits.calls) calls.add(MethodCall(id, ReferenceUtil.getMethodDescriptor(instruction.reference as MethodReference), offset))
                                                else warnings.add("Batas referensi pemanggilan tercapai; Find usage hanya sebagian.")
                                            }
                                            offset += instruction.codeUnits
                                        }
                                    }
                                    methods.add(CodeMethod(descriptor, cls.type, method.name, archive, entry.name,
                                        AccessFlags.getAccessFlagsForMethod(method.accessFlags).joinToString(" ") { it.toString() },
                                        implementation != null, lines, count, truncated))
                                }
                            }
                            }
                            // Strings found in the pools should lead back to code even when the index is full.
                            // A DEX can only load strings from its own pool, so pools without findings are skipped.
                            if (traces != null && dexHasFindings) xref@ for (cls in dex.classes) {
                                if (cls.type in indexedTypes) continue
                                for (method in cls.methods) {
                                    val implementation = method.implementation ?: continue
                                    val id = "$archive:${entry.name}:${ReferenceUtil.getMethodDescriptor(method)}"
                                    for (instruction in implementation.instructions) {
                                        if (++xrefVisited > limits.xrefInstructions) { warnings.add("Batas pemindaian referensi string tercapai; tautan \"Dipakai di\" hanya sebagian."); break@xref }
                                        if (xrefVisited % 4096 == 0) checkCancelled()
                                        if (instruction is ReferenceInstruction && instruction.reference is StringReference) traces.reference((instruction.reference as StringReference).string, id)
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { warnings.add("${archive}/${entry.name} tidak dapat diindeks seluruhnya: DEX rusak atau belum didukung.") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings.add("Arsip $archive tidak dapat dibaca untuk indeks kode.") }
        }
        return result()
    }
}

@Suppress("DEPRECATION")
private fun renderInstruction(offset: Int, instruction: Instruction): String {
    val registers = when (instruction) {
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount).joinToString { "v$it" }
        is RegisterRangeInstruction -> "v${instruction.startRegister} .. (${instruction.registerCount} register)"
        is ThreeRegisterInstruction -> "v${instruction.registerA}, v${instruction.registerB}, v${instruction.registerC}"
        is TwoRegisterInstruction -> "v${instruction.registerA}, v${instruction.registerB}"
        is OneRegisterInstruction -> "v${instruction.registerA}"
        else -> ""
    }
    val reference = if (instruction is ReferenceInstruction) ReferenceUtil.getReferenceString(instruction.reference)?.take(500).orEmpty() else ""
    val literal = when (instruction) {
        is WideLiteralInstruction -> "literal=${instruction.wideLiteral}"
        else -> ""
    }
    val branch = if (instruction is OffsetInstruction) "target=0x${(offset + instruction.codeOffset).toString(16)}" else ""
    return "0x${offset.toString(16)}  ${instruction.opcode.name.lowercase().replace('_', '-')} " + listOf(registers, reference, literal, branch).filter { it.isNotEmpty() }.joinToString(" · ")
}
