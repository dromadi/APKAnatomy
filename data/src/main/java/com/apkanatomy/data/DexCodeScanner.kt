package com.apkanatomy.data

import com.apkanatomy.domain.*
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
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
    /** Pseudo-code lines kept across all methods; the app's own code is indexed first, so it is covered first. */
    val pseudoLines: Int = 80_000,
    /** Instructions read beyond the index, only to link strings to the methods that load them and to find behaviours. */
    val xrefInstructions: Int = 5_000_000)

@Suppress("DEPRECATION")
internal class DexCodeScanner(private val limits: CodeLimits = CodeLimits()) {
    init { require(limits.dexBytes > 0 && limits.totalBytes > 0 && limits.classes > 0 && limits.methods > 0 && limits.visitedInstructions > 0 && limits.displayedInstructions > 0 && limits.calls > 0) }
    /**
     * @param traces receives every string of every DEX read, for endpoint and secret detection.
     * @param behaviors receives the sensitive calls and strings of every method read.
     * @param entryClasses manifest component classes (dotted names); they are indexed before anything else, and
     *   their packages' first two segments join [preferredPackage] as namespaces indexed before libraries.
     */
    fun scan(files: List<File>, preferredPackage: String? = null, traces: TraceCollector? = null,
        behaviors: BehaviorCollector? = null, entryClasses: Collection<String> = emptyList(), checkCancelled: () -> Unit = {}): CodeIndex {
        val entryTypes = entryClasses.mapTo(HashSet()) { "L" + it.replace('.', '/') + ";" }
        val prefixes = preferredPrefixes(preferredPackage, entryClasses)
        val classes = mutableListOf<CodeClass>()
        val methods = mutableListOf<CodeMethod>()
        val calls = mutableListOf<MethodCall>()
        val warnings = linkedSetOf<String>()
        var totalBytes = 0L
        var visited = 0
        var displayed = 0
        var pseudoLines = 0
        // Package names are gathered from every DEX that fits the byte budget, even after the
        // class/method index is full, because SDK detection needs the whole namespace.
        val packages = sortedSetOf<String>()
        val strings = mutableListOf<StringUse>()
        var definedClasses = 0
        var shortNamed = 0
        var xrefVisited = 0
        val packageClasses = HashMap<String, Int>()
        fun result() = CodeIndex(classes, methods, calls, warnings.toList(), packages.toList(), definedClasses, shortNamed, strings, packageClasses)
        // 1. Load every DEX within the byte budget: packages and string pools cover all of them.
        val loaded = mutableListOf<LoadedDex>()
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
                                val pkg = name.substringBeforeLast('/', "").replace('/', '.')
                                packages.add(pkg)
                                packageClasses.merge(pkg, 1, Int::plus)
                                definedClasses++
                                if (name.substringAfterLast('/').substringBefore('$').length <= 2) shortNamed++
                            }
                            val current = LoadedDex(archive, entry.name, dex)
                            if (traces != null) dex.stringSection.forEachIndexed { i, s ->
                                if (i % 4096 == 0) checkCancelled()
                                traces.scan(s, "$archive/${entry.name}")
                                if (!current.hasFindings && traces.isInteresting(s)) current.hasFindings = true
                            }
                            loaded += current
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { warnings.add("${archive}/${entry.name} tidak dapat diindeks seluruhnya: DEX rusak atau belum didukung.") }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings.add("Arsip $archive tidak dapat dibaca untuk indeks kode.") }
        }

        // 2. Index across all DEX files by priority: components first, then the app's own namespaces, then libraries.
        // Apps spread their code over classes2.dex, classes3.dex..., so a per-file order would let libraries fill the index.
        fun rank(type: String) = when { type in entryTypes -> 0; prefixes.any { type.startsWith(it) } -> 1; else -> 2 }
        indexing@ for (r in 0..2) for (current in loaded) {
            val archive = current.archive
            try {
                for (cls in current.dex.classes) {
                    if (rank(cls.type) != r) continue
                    checkCancelled()
                    if (classes.size >= limits.classes) { warnings.add("Batas class tercapai; indeks hanya sebagian."); break@indexing }
                    var complete = true
                    classes.add(CodeClass(cls.type, cls.superclass, cls.interfaces.toList(), archive, current.name))
                    for (method in cls.methods) {
                        checkCancelled()
                        if (methods.size >= limits.methods) { warnings.add("Batas method tercapai; indeks hanya sebagian."); break@indexing }
                        val descriptor = ReferenceUtil.getMethodDescriptor(method)
                        val id = "$archive:${current.name}:$descriptor"
                        val implementation = method.implementation
                        val lines = mutableListOf<String>()
                        var count = 0
                        var offset = 0
                        var truncated = false
                        val sensitiveCalls = ArrayList<Pair<String, String>>()
                        val strs = ArrayList<String>()
                        val ints = ArrayList<Long>()
                        if (implementation != null) {
                            for (instruction in implementation.instructions) {
                                if (visited >= limits.visitedInstructions) { warnings.add("Batas pemindaian instruksi tercapai; Find usage hanya mencakup instruksi terbaca."); truncated = true; complete = false; break }
                                if (visited % 256 == 0) checkCancelled()
                                visited++; count++
                                if (lines.size < 120 && displayed < limits.displayedInstructions) {
                                    lines.add(renderInstruction(offset, instruction)); displayed++
                                } else truncated = true
                                if (instruction is ReferenceInstruction && instruction.reference is StringReference) {
                                    val literal = (instruction.reference as StringReference).string
                                    traces?.reference(literal, id)
                                    if (strs.size < MAX_METHOD_STRINGS) strs.add(literal)
                                    if (strings.size < limits.strings) strings.add(StringUse(id, literal.take(300)))
                                    else warnings.add("Batas ${limits.strings} string literal tercapai; pencarian string hanya sebagian.")
                                }
                                if (ints.size < MAX_METHOD_STRINGS) literal(instruction)?.let { ints += it }
                                if (instruction.opcode.name.startsWith("invoke", ignoreCase = true) && instruction is ReferenceInstruction && instruction.reference is MethodReference) {
                                    val target = instruction.reference as MethodReference
                                    if (behaviors != null && behaviors.watches(target.definingClass)) sensitiveCalls.add(target.definingClass to target.name)
                                    if (calls.size < limits.calls) calls.add(MethodCall(id, ReferenceUtil.getMethodDescriptor(target), offset))
                                    else warnings.add("Batas referensi pemanggilan tercapai; Find usage hanya sebagian.")
                                }
                                offset += instruction.codeUnits
                            }
                        }
                        if (behaviors != null && complete) {
                            behaviors.method(id, sensitiveCalls, strs, count, ints)
                            pattern(cls, method)?.let { behaviors.pattern(it, id) }
                            current.reported.add(id)
                        }
                        val pseudo = if (implementation != null && pseudoLines < limits.pseudoLines) PseudoJava.render(method).also { pseudoLines += it.size } else emptyList()
                        methods.add(CodeMethod(descriptor, cls.type, method.name, archive, current.name,
                            AccessFlags.getAccessFlagsForMethod(method.accessFlags).joinToString(" ") { it.toString() },
                            implementation != null, lines, count, truncated, pseudo))
                    }
                    if (complete) current.indexedTypes.add(cls.type)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings.add("${archive}/${current.name} tidak dapat diindeks seluruhnya: DEX rusak atau belum didukung.") }
        }

        // 3. Strings found in the pools should lead back to code, and behaviours be found, even beyond the index.
        // A DEX can only load strings from its own pool, so string links are skipped for pools without findings.
        xref@ for (current in loaded) {
            val linkStrings = traces != null && current.hasFindings
            if (!linkStrings && behaviors == null) continue
            try {
                for (cls in current.dex.classes) {
                    if (cls.type in current.indexedTypes) continue
                    for (method in cls.methods) {
                        val implementation = method.implementation ?: continue
                        val id = "${current.archive}:${current.name}:${ReferenceUtil.getMethodDescriptor(method)}"
                        val sensitiveCalls = ArrayList<Pair<String, String>>(0)
                        val strs = ArrayList<String>(0)
                        val ints = ArrayList<Long>(0)
                        var count = 0
                        for (instruction in implementation.instructions) {
                            if (++xrefVisited > limits.xrefInstructions) {
                                warnings.add("Batas pemindaian tambahan tercapai; tautan \"Dipakai di\" dan tab Perilaku hanya sebagian.")
                                behaviors?.note("Batas ${limits.xrefInstructions} instruksi tercapai; sebagian kode tidak diperiksa.")
                                break@xref
                            }
                            if (xrefVisited % 4096 == 0) checkCancelled()
                            count++
                            if (behaviors != null && ints.size < MAX_METHOD_STRINGS) literal(instruction)?.let { ints += it }
                            if (instruction !is ReferenceInstruction) continue
                            when (val reference = instruction.reference) {
                                is StringReference -> {
                                    if (linkStrings) traces!!.reference(reference.string, id)
                                    if (behaviors != null && strs.size < MAX_METHOD_STRINGS) strs.add(reference.string)
                                }
                                is MethodReference -> if (behaviors != null && behaviors.watches(reference.definingClass)) sensitiveCalls.add(reference.definingClass to reference.name)
                            }
                        }
                        if (behaviors != null && id !in current.reported) {
                            behaviors.method(id, sensitiveCalls, strs, count, ints)
                            pattern(cls, method)?.let { behaviors.pattern(it, id) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { warnings.add("${current.archive}/${current.name} tidak dapat diindeks seluruhnya: DEX rusak atau belum didukung.") }
        }
        return result()
    }
}

private const val MAX_METHOD_STRINGS = 64

/** The value of a const/const-wide instruction, for rules that look at flag constants. */
private fun literal(instruction: Instruction): Long? = when {
    !instruction.opcode.name.startsWith("const", ignoreCase = true) -> null
    instruction is WideLiteralInstruction -> instruction.wideLiteral
    instruction is NarrowLiteralInstruction -> instruction.narrowLiteral.toLong() and 0xffffffffL
    else -> null
}

/** A DEX file held in memory between the scan phases; [indexedTypes] were read completely while indexing. */
private class LoadedDex(val archive: String, val name: String, val dex: DexBackedDexFile) {
    var hasFindings = false
    val indexedTypes = HashSet<String>()
    /** Methods already given to the behaviour collector, so the last pass does not count them twice. */
    val reported = HashSet<String>()
}

/**
 * DEX type prefixes of the app's own code: the package name, and the first two segments of it when components
 * live there too. The application ID often differs from the code namespace (org.fdroid.fdroid vs org.fdroid.*).
 */
internal fun preferredPrefixes(packageName: String?, entryClasses: Collection<String>): List<String> {
    val name = packageName?.takeIf { it.isNotBlank() } ?: return emptyList()
    val root = name.split('.').take(2).joinToString(".")
    val roots = listOf(name) + listOf(root).filter { r -> r != name && '.' in r && entryClasses.any { it.startsWith("$r.") } }
    return roots.map { "L" + it.replace('.', '/') + "/" }
}

private val TRUST_MANAGERS = setOf("Ljavax/net/ssl/X509TrustManager;", "Ljavax/net/ssl/X509ExtendedTrustManager;")

/**
 * Whole-method patterns: checkServerTrusted of a TrustManager that only returns, and HostnameVerifier.verify
 * that only returns true. Both accept any certificate.
 */
private fun pattern(cls: ClassDef, method: Method): String? {
    val implementation = method.implementation ?: return null
    return when {
        method.name == "checkServerTrusted" && (cls.interfaces.any { it in TRUST_MANAGERS } || cls.superclass in TRUST_MANAGERS) -> {
            val opcodes = implementation.instructions.take(2).map { it.opcode }
            if (opcodes == listOf(Opcode.RETURN_VOID)) BehaviorPatterns.TRUST_ALL else null
        }
        method.name == "verify" && method.returnType == "Z" && "Ljavax/net/ssl/HostnameVerifier;" in cls.interfaces -> {
            val instructions = implementation.instructions.take(3).toList()
            val constant = instructions.firstOrNull() as? NarrowLiteralInstruction
            if (instructions.size == 2 && constant != null && constant.narrowLiteral == 1 && instructions[1].opcode == Opcode.RETURN) BehaviorPatterns.HOSTNAME_ALL else null
        }
        else -> null
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

/**
 * Only the packages of the classes defined in [files], for quick SDK detection across many apps.
 * Returns a note when some DEX could not be read or exceeded [maxBytes] in total.
 */
internal fun dexPackages(files: List<File>, maxBytes: Long = 64L * 1024 * 1024, checkCancelled: () -> Unit = {}): Pair<Set<String>, String?> {
    val packages = HashSet<String>()
    var total = 0L
    var note: String? = null
    for (file in files) try {
        ZipFile(file).use { zip ->
            for (entry in zip.entries().asSequence().filter { Regex("classes([0-9]+)?\\.dex").matches(it.name) }) {
                checkCancelled()
                if (entry.size !in 112..(maxBytes - total)) { note = "Sebagian DEX dilewati karena batas ukuran."; continue }
                total += entry.size
                val bytes = zip.getInputStream(entry).use { input -> ByteArrayOutputStream().use { out -> copyBounded(input, out, entry.size); out.toByteArray() } }
                DexBackedDexFile.fromInputStream(null, bytes.inputStream()).classes.forEach { cls ->
                    packages += cls.type.removePrefix("L").substringBeforeLast('/', "").replace('/', '.')
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { note = "Sebagian kode tidak dapat dibaca." }
    return packages to note
}
