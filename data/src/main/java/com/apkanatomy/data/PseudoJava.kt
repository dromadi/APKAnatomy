package com.apkanatomy.data

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference

/**
 * Turns a method's DEX instructions into Java-like pseudo-code, one statement per instruction:
 * registers keep their names (v0, p0 for parameters, `this`), branches stay as `goto` labels, and the
 * result of a call or `new` is folded into an assignment. It is meant for reading, not for compiling.
 */
internal object PseudoJava {
    fun render(method: Method, maxLines: Int = 120): List<String> {
        val implementation = method.implementation ?: return emptyList()
        val instructions = implementation.instructions.toList()
        val names = registerNames(method, implementation.registerCount)
        fun r(register: Int) = names[register] ?: "v$register"

        var offset = 0
        val offsets = instructions.map { val at = offset; offset += it.codeUnits; at }
        val targets = instructions.withIndex().filter { it.value is OffsetInstruction && !it.value.op().endsWith("_PAYLOAD") }
            .mapTo(HashSet()) { offsets[it.index] + (it.value as OffsetInstruction).codeOffset }

        val out = mutableListOf<String>()
        var pendingCall: String? = null
        val pendingNew = HashMap<Int, String>()
        fun flush() { pendingCall?.let { out += "$it;" }; pendingCall = null }
        for ((i, ins) in instructions.withIndex()) {
            if (out.size >= maxLines) { out += "// … dipotong setelah $maxLines baris"; break }
            val at = offsets[i]
            if (at in targets) { flush(); out += "L_${at.toString(16)}:" }
            val name = ins.op()
            if (name.startsWith("MOVE_RESULT")) {
                val call = pendingCall
                pendingCall = null
                out += if (call != null) "${r((ins as OneRegisterInstruction).registerA)} = $call;" else "${r((ins as OneRegisterInstruction).registerA)} = <hasil>;"
                continue
            }
            flush()
            // An unusual instruction shape must not cost the whole method its pseudo-code.
            val line = try { statement(ins, at, ::r, pendingNew) } catch (_: RuntimeException) { "// ${name.lowercase().replace('_', '-')}" }
            line?.let { line ->
                if (line.startsWith(CALL)) pendingCall = line.removePrefix(CALL) else out += line
            }
        }
        flush()
        pendingNew.forEach { (register, type) -> out += "${r(register)} = new $type; // konstruktor tidak terlihat" }
        return out
    }

    private const val CALL = "\u0000call:"

    /** dexlib2 names opcodes as in smali ("const/4", "add-int/2addr"); this gives "CONST_4", "ADD_INT_2ADDR". */
    private fun Instruction.op(): String = opcode.name.uppercase().replace('-', '_').replace('/', '_')

    /** p0..pN for parameters (with `this` for p0 of instance methods); wide parameters take two registers. */
    private fun registerNames(method: Method, registerCount: Int): Map<Int, String> {
        val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
        val sizes = (if (isStatic) emptyList() else listOf(1)) + method.parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
        var register = registerCount - sizes.sum()
        val names = HashMap<Int, String>()
        sizes.forEachIndexed { index, size ->
            names[register] = if (!isStatic && index == 0) "this" else "p$index"
            register += size
        }
        return names
    }

    private val BINARY = mapOf("ADD" to "+", "SUB" to "-", "MUL" to "*", "DIV" to "/", "REM" to "%", "AND" to "&", "OR" to "|",
        "XOR" to "^", "SHL" to "<<", "SHR" to ">>", "USHR" to ">>>")
    private val COMPARE = mapOf("EQ" to "==", "NE" to "!=", "LT" to "<", "GE" to ">=", "GT" to ">", "LE" to "<=")

    private fun statement(ins: Instruction, at: Int, r: (Int) -> String, pendingNew: HashMap<Int, String>): String? {
        val name = ins.op()
        val a = (ins as? OneRegisterInstruction)?.registerA
        fun label(o: Int) = "L_${(at + o).toString(16)}"
        return when {
            ins.opcode == Opcode.NOP || name.endsWith("_PAYLOAD") -> null
            name.startsWith("CONST_STRING") -> "${r(a!!)} = ${quote(((ins as ReferenceInstruction).reference as StringReference).string)};"
            name == "CONST_CLASS" -> "${r(a!!)} = ${type((ins as ReferenceInstruction).reference as TypeReference)}.class;"
            name.startsWith("CONST_WIDE") -> "${r(a!!)} = ${(ins as WideLiteralInstruction).wideLiteral}L;"
            name.startsWith("CONST") && ins is NarrowLiteralInstruction -> "${r(a!!)} = ${ins.narrowLiteral};"
            name == "MOVE_EXCEPTION" -> "${r(a!!)} = <exception>;"
            name.startsWith("MOVE") -> "${r(a!!)} = ${r((ins as TwoRegisterInstruction).registerB)};"
            name.startsWith("RETURN_VOID") -> "return;"
            name.startsWith("RETURN") -> "return ${r(a!!)};"
            name == "NEW_INSTANCE" -> { pendingNew[a!!] = type((ins as ReferenceInstruction).reference as TypeReference); null }
            name == "NEW_ARRAY" -> { val t = type((ins as ReferenceInstruction).reference as TypeReference); "${r(a!!)} = new ${t.removeSuffix("[]")}[${r((ins as TwoRegisterInstruction).registerB)}];" }
            name == "ARRAY_LENGTH" -> "${r(a!!)} = ${r((ins as TwoRegisterInstruction).registerB)}.length;"
            name == "CHECK_CAST" -> "${r(a!!)} = (${type((ins as ReferenceInstruction).reference as TypeReference)}) ${r(a)};"
            name == "INSTANCE_OF" -> "${r(a!!)} = ${r((ins as TwoRegisterInstruction).registerB)} instanceof ${type((ins as ReferenceInstruction).reference as TypeReference)};"
            name == "THROW" -> "throw ${r(a!!)};"
            name == "MONITOR_ENTER" -> "synchronized (${r(a!!)}) {"
            name == "MONITOR_EXIT" -> "} // akhir synchronized (${r(a!!)})"
            name.startsWith("GOTO") -> "goto ${label((ins as OffsetInstruction).codeOffset)};"
            name.startsWith("IF_") && name.endsWith("Z") -> "if (${r(a!!)} ${COMPARE[name.removePrefix("IF_").removeSuffix("Z")]} 0) goto ${label((ins as OffsetInstruction).codeOffset)};"
            name.startsWith("IF_") -> { ins as TwoRegisterInstruction; "if (${r(ins.registerA)} ${COMPARE[name.removePrefix("IF_")]} ${r(ins.registerB)}) goto ${label((ins as OffsetInstruction).codeOffset)};" }
            name == "PACKED_SWITCH" || name == "SPARSE_SWITCH" -> "switch (${r(a!!)}) { … } // tabel di ${label((ins as OffsetInstruction).codeOffset)}"
            name.startsWith("FILLED_NEW_ARRAY") -> {
                val t = type((ins as ReferenceInstruction).reference as TypeReference)
                CALL + "new ${t.removeSuffix("[]")}[] { ${registers(ins).joinToString { r(it) }} }"
            }
            name == "FILL_ARRAY_DATA" -> "${r(a!!)} = { … }; // isi array konstan"
            name.startsWith("AGET") -> { ins as ThreeRegisterInstruction; "${r(ins.registerA)} = ${r(ins.registerB)}[${r(ins.registerC)}];" }
            name.startsWith("APUT") -> { ins as ThreeRegisterInstruction; "${r(ins.registerB)}[${r(ins.registerC)}] = ${r(ins.registerA)};" }
            name.startsWith("IGET") -> { val f = (ins as ReferenceInstruction).reference as FieldReference; "${r(a!!)} = ${r((ins as TwoRegisterInstruction).registerB)}.${f.name};" }
            name.startsWith("IPUT") -> { val f = (ins as ReferenceInstruction).reference as FieldReference; "${r((ins as TwoRegisterInstruction).registerB)}.${f.name} = ${r(a!!)};" }
            name.startsWith("SGET") -> { val f = (ins as ReferenceInstruction).reference as FieldReference; "${r(a!!)} = ${simple(f.definingClass)}.${f.name};" }
            name.startsWith("SPUT") -> { val f = (ins as ReferenceInstruction).reference as FieldReference; "${simple(f.definingClass)}.${f.name} = ${r(a!!)};" }
            name.startsWith("INVOKE") && ins is ReferenceInstruction && ins.reference is MethodReference -> invoke(name, ins.reference as MethodReference, registers(ins), r, pendingNew)
            name.startsWith("CMP") -> { ins as ThreeRegisterInstruction; "${r(ins.registerA)} = compare(${r(ins.registerB)}, ${r(ins.registerC)});" }
            name.startsWith("NEG_") -> "${r(a!!)} = -${r((ins as TwoRegisterInstruction).registerB)};"
            name.startsWith("NOT_") -> "${r(a!!)} = ~${r((ins as TwoRegisterInstruction).registerB)};"
            "_TO_" in name -> "${r(a!!)} = (${name.substringAfter("_TO_").lowercase()}) ${r((ins as TwoRegisterInstruction).registerB)};"
            name.startsWith("RSUB_INT") -> "${r(a!!)} = ${(ins as NarrowLiteralInstruction).narrowLiteral} - ${r((ins as TwoRegisterInstruction).registerB)};"
            BINARY.keys.any { name.startsWith(it + "_") } -> {
                val op = BINARY.getValue(name.substringBefore('_'))
                when {
                    name.endsWith("_2ADDR") -> { ins as TwoRegisterInstruction; "${r(ins.registerA)} = ${r(ins.registerA)} $op ${r(ins.registerB)};" }
                    "_LIT" in name -> "${r(a!!)} = ${r((ins as TwoRegisterInstruction).registerB)} $op ${(ins as NarrowLiteralInstruction).narrowLiteral};"
                    else -> { ins as ThreeRegisterInstruction; "${r(ins.registerA)} = ${r(ins.registerB)} $op ${r(ins.registerC)};" }
                }
            }
            else -> "// ${name.lowercase().replace('_', '-')}"
        }
    }

    private fun registers(ins: Instruction): List<Int> = when (ins) {
        is FiveRegisterInstruction -> listOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG).take(ins.registerCount)
        is RegisterRangeInstruction -> (ins.startRegister until ins.startRegister + ins.registerCount).toList()
        else -> emptyList()
    }

    private fun invoke(name: String, target: MethodReference, regs: List<Int>, r: (Int) -> String, pendingNew: HashMap<Int, String>): String {
        val isStatic = name.startsWith("INVOKE_STATIC")
        // Arguments skip the second half of wide (long/double) values.
        val args = mutableListOf<String>()
        var index = if (isStatic) 0 else 1
        for (type in target.parameterTypes) {
            if (index < regs.size) args += r(regs[index])
            index += if (type.toString() == "J" || type.toString() == "D") 2 else 1
        }
        val list = args.joinToString(", ")
        if (target.name == "<init>" && !isStatic && regs.isNotEmpty()) {
            val receiver = regs[0]
            val created = pendingNew.remove(receiver)
            return when {
                created != null -> "${r(receiver)} = new $created($list);"
                name.startsWith("INVOKE_DIRECT") && r(receiver) == "this" -> "super($list); // atau this(...)"
                else -> "${r(receiver)}.<init>($list);"
            }
        }
        val call = when {
            isStatic -> "${simple(target.definingClass)}.${target.name}($list)"
            name.startsWith("INVOKE_SUPER") -> "super.${target.name}($list)"
            else -> "${r(regs.firstOrNull() ?: 0)}.${target.name}($list)"
        }
        return if (target.returnType == "V") "$call;" else CALL + call
    }

    private fun type(reference: TypeReference) = simple(reference.type)

    /** "Ljava/lang/String;" → "String", "[I" → "int[]". */
    fun simple(descriptor: String): String {
        val dims = descriptor.takeWhile { it == '[' }.length
        val base = when (val t = descriptor.drop(dims)) {
            "Z" -> "boolean"; "B" -> "byte"; "S" -> "short"; "C" -> "char"; "I" -> "int"; "J" -> "long"; "F" -> "float"; "D" -> "double"; "V" -> "void"
            // Outer$Inner reads as Outer.Inner; synthetic names such as Main$$Lambda keep their "$$".
            else -> t.removePrefix("L").removeSuffix(";").substringAfterLast('/').replace(Regex("(?<!\\$)\\$(?!\\$)"), ".")
        }
        return base + "[]".repeat(dims)
    }

    private fun quote(text: String): String {
        val cut = if (text.length > 80) text.take(80) + "…" else text
        return "\"" + cut.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
