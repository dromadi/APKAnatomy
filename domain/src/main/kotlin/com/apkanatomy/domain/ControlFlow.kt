package com.apkanatomy.domain

/** How an instruction passes control on. */
enum class FlowKind { NEXT, GOTO, BRANCH, SWITCH, RETURN, THROW }

/**
 * One DEX instruction for building a control-flow graph. [offset] and [size] are in 16-bit code units;
 * [targets] are absolute offsets (a branch's target, or a switch's case targets with [caseKeys]).
 */
data class FlowInstruction(val offset: Int, val size: Int, val text: String, val kind: FlowKind = FlowKind.NEXT,
    val targets: List<Int> = emptyList(), val caseKeys: List<Int> = emptyList())

/** A try range [start, end) in code units and its handlers: exception type (null catches everything) and handler offset. */
data class FlowTry(val start: Int, val end: Int, val handlers: List<Pair<String?, Int>>)

enum class EdgeKind { NEXT, JUMP, TRUE, FALSE, CASE, DEFAULT, CATCH }

/** An edge to block [to]; [label] is a case key or an exception type. */
data class FlowEdge(val to: Int, val kind: EdgeKind, val label: String? = null) : java.io.Serializable

/** A basic block: instructions that always run together, from [start] to its last instruction. [exit] is "return" or "throw". */
data class FlowBlock(val index: Int, val start: Int, val lines: List<String>, val hiddenLines: Int, val edges: List<FlowEdge>, val exit: String?) : java.io.Serializable

data class ControlFlow(val blocks: List<FlowBlock>, val truncated: Boolean, val note: String? = null) : java.io.Serializable {
    /**
     * McCabe's cyclomatic complexity as decisions + 1: every extra way out of a block (an if, each switch case)
     * adds one. Exception edges are left out, as most tools do.
     */
    val complexity: Int get() = 1 + blocks.sumOf { b -> (b.edges.count { it.kind != EdgeKind.CATCH }.let { maxOf(it, 1) } - 1) }
    /** An edge going back to an earlier (or the same) block: the end of a loop. */
    fun isBack(from: FlowBlock, edge: FlowEdge): Boolean = edge.kind != EdgeKind.CATCH && blocks.getOrNull(edge.to)?.let { it.start <= from.start } == true
    val loops: Int get() = blocks.sumOf { b -> b.edges.count { isBack(b, it) } }
}

/**
 * Splits [instructions] (in offset order) into basic blocks: a block starts at the method's first instruction,
 * at every jump or case target, after every jump, switch, return or throw, and at try boundaries and handlers.
 * At most [maxBlocks] blocks and [maxLinesPerBlock] lines each are kept; edges into dropped blocks are removed.
 */
fun controlFlow(instructions: List<FlowInstruction>, tries: List<FlowTry> = emptyList(), maxBlocks: Int = 300, maxLinesPerBlock: Int = 40): ControlFlow {
    if (instructions.isEmpty()) return ControlFlow(emptyList(), truncated = false, note = "Method ini tidak memiliki instruksi.")
    val offsets = instructions.mapTo(HashSet()) { it.offset }
    val leaders = sortedSetOf(instructions.first().offset)
    instructions.forEachIndexed { i, ins ->
        ins.targets.filter { it in offsets }.forEach { leaders += it }
        if (ins.kind != FlowKind.NEXT) instructions.getOrNull(i + 1)?.let { leaders += it.offset }
    }
    tries.forEach { t ->
        if (t.start in offsets) leaders += t.start
        if (t.end in offsets) leaders += t.end
        t.handlers.forEach { (_, h) -> if (h in offsets) leaders += h }
    }
    // Group instructions into blocks.
    val groups = mutableListOf<MutableList<FlowInstruction>>()
    for (ins in instructions) {
        if (ins.offset in leaders || groups.isEmpty()) groups += mutableListOf(ins) else groups.last() += ins
    }
    val blockAt = groups.withIndex().associate { (i, g) -> g.first().offset to i }
    val kept = groups.take(maxBlocks)
    val blocks = kept.mapIndexed { i, group ->
        val last = group.last()
        val next = if (i + 1 < groups.size) i + 1 else null
        val edges = mutableListOf<FlowEdge>()
        fun to(offset: Int) = blockAt[offset]
        when (last.kind) {
            FlowKind.NEXT -> next?.let { edges += FlowEdge(it, EdgeKind.NEXT) }
            FlowKind.GOTO -> last.targets.firstOrNull()?.let(::to)?.let { edges += FlowEdge(it, EdgeKind.JUMP) }
            FlowKind.BRANCH -> {
                last.targets.firstOrNull()?.let(::to)?.let { edges += FlowEdge(it, EdgeKind.TRUE) }
                next?.let { edges += FlowEdge(it, EdgeKind.FALSE) }
            }
            FlowKind.SWITCH -> {
                // Several keys can share a target; they become one edge labelled with all of them.
                last.targets.zip(last.caseKeys.ifEmpty { last.targets.indices.toList() }).groupBy({ it.first }, { it.second })
                    .forEach { (target, keys) -> to(target)?.let { edges += FlowEdge(it, EdgeKind.CASE, keys.joinToString()) } }
                next?.let { edges += FlowEdge(it, EdgeKind.DEFAULT) }
            }
            FlowKind.RETURN, FlowKind.THROW -> Unit
        }
        val start = group.first().offset
        tries.filter { start >= it.start && start < it.end }.forEach { t ->
            t.handlers.forEach { (type, h) -> to(h)?.let { edges += FlowEdge(it, EdgeKind.CATCH, type?.let(::simpleType) ?: "semua") } }
        }
        val inRange = edges.filter { it.to < kept.size }
        val exit = when (last.kind) { FlowKind.RETURN -> "return"; FlowKind.THROW -> "throw"; else -> null }
        FlowBlock(i, start, group.take(maxLinesPerBlock).map { it.text }, (group.size - maxLinesPerBlock).coerceAtLeast(0), inRange.distinct(), exit)
    }
    val truncated = groups.size > maxBlocks
    return ControlFlow(blocks, truncated, if (truncated) "Method terlalu besar: hanya $maxBlocks dari ${groups.size} blok ditampilkan." else null)
}

/** "Ljava/io/IOException;" → "IOException". */
private fun simpleType(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").substringAfterLast('/')
