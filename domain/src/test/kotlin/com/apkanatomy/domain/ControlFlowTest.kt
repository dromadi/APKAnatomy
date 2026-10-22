package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class ControlFlowTest {
    private fun ins(offset: Int, text: String, kind: FlowKind = FlowKind.NEXT, vararg targets: Int, keys: List<Int> = emptyList(), size: Int = 1) =
        FlowInstruction(offset, size, text, kind, targets.toList(), keys)

    @Test fun ifElseLoopAndReturn() {
        // 0: i = 0; 1: if (i >= n) goto 5; 2..3: body; 4: goto 1; 5: if (x == 0) goto 7; 6: return 1; 7: return 0
        val flow = controlFlow(listOf(
            ins(0, "const/4 v0, 0"),
            ins(1, "if-ge v0, p0", FlowKind.BRANCH, 5),
            ins(2, "add-int/lit8 v0, v0, 1"), ins(3, "nop"),
            ins(4, "goto", FlowKind.GOTO, 1),
            ins(5, "if-eqz p1", FlowKind.BRANCH, 7),
            ins(6, "return v1", FlowKind.RETURN),
            ins(7, "throw v2", FlowKind.THROW)))
        assertEquals(listOf(0, 1, 2, 5, 6, 7), flow.blocks.map { it.start })
        assertEquals(listOf(FlowEdge(1, EdgeKind.NEXT)), flow.blocks[0].edges)
        assertEquals(listOf(FlowEdge(3, EdgeKind.TRUE), FlowEdge(2, EdgeKind.FALSE)), flow.blocks[1].edges)
        assertEquals(listOf("add-int/lit8 v0, v0, 1", "nop", "goto"), flow.blocks[2].lines)
        assertEquals(listOf(FlowEdge(1, EdgeKind.JUMP)), flow.blocks[2].edges)
        assertTrue(flow.isBack(flow.blocks[2], flow.blocks[2].edges.single()))
        assertEquals(1, flow.loops)
        assertEquals("return", flow.blocks[4].exit)
        assertEquals("throw", flow.blocks[5].exit)
        assertTrue(flow.blocks[5].edges.isEmpty())
        // Two ifs: complexity 3.
        assertEquals(3, flow.complexity)
        assertFalse(flow.truncated)
    }

    @Test fun switchCasesShareEdgesAndTryAddsCatchEdges() {
        val flow = controlFlow(listOf(
            ins(0, "invoke-static", size = 3),
            ins(3, "packed-switch v0", FlowKind.SWITCH, 6, 8, 6, keys = listOf(1, 2, 3), size = 3),
            ins(6, "return-void", FlowKind.RETURN),
            ins(7, "return-void", FlowKind.RETURN),
            ins(8, "move-exception v1"),
            ins(9, "return-void", FlowKind.RETURN)),
            tries = listOf(FlowTry(0, 6, listOf("Ljava/io/IOException;" to 8, null to 9))))
        // The try ends at 6, so 0 and 3 are one block even though 3 is a switch (a block ends at, not before, it).
        assertEquals(listOf(0, 6, 7, 8, 9), flow.blocks.map { it.start })
        val head = flow.blocks[0]
        assertEquals(listOf(FlowEdge(1, EdgeKind.CASE, "1, 3"), FlowEdge(3, EdgeKind.CASE, "2"), FlowEdge(1, EdgeKind.DEFAULT),
            FlowEdge(3, EdgeKind.CATCH, "IOException"), FlowEdge(4, EdgeKind.CATCH, "semua")), head.edges)
        // Two case edges + default: two decisions; catch edges do not count.
        assertEquals(3, flow.complexity)
        // Block 7 is unreachable but still shown.
        assertTrue(flow.blocks[2].edges.isEmpty())
    }

    @Test fun limitsBlocksAndLines() {
        val many = (0 until 10).map { ins(it, "return-void", FlowKind.RETURN) }
        val flow = controlFlow(many, maxBlocks = 4)
        assertEquals(4, flow.blocks.size)
        assertTrue(flow.truncated)
        assertNotNull(flow.note)
        val long = controlFlow((0 until 50).map { ins(it, "nop") }, maxLinesPerBlock = 10).blocks.single()
        assertEquals(10, long.lines.size)
        assertEquals(40, long.hiddenLines)
        assertNotNull(controlFlow(emptyList()).note)
    }
}
