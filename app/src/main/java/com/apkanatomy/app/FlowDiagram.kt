package com.apkanatomy.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.*

/** How many jump lanes the gutter left of the blocks has; further jumps are only listed on their block. */
private const val LANES = 8

/**
 * A method's control-flow graph: basic blocks stacked in code order, with jumps drawn as lanes on the left.
 * Falling through to the next block needs no line; loops (jumps back) are dashed.
 */
@Composable
internal fun FlowDiagram(flow: ControlFlow) {
    val colors = MaterialTheme.colorScheme
    fun color(kind: EdgeKind, back: Boolean) = when {
        back -> colors.secondary
        kind == EdgeKind.TRUE -> colors.tertiary
        kind == EdgeKind.CATCH -> colors.error
        kind == EdgeKind.CASE -> colors.primary
        else -> colors.outline
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${flow.blocks.size} blok · kompleksitas ${flow.complexity} · ${flow.loops} perulangan", style = MaterialTheme.typography.titleSmall)
                Text("Blok dasar adalah deretan instruksi yang selalu jalan bersama: masuk dari atas, keluar dari bawah. " +
                    "Kompleksitas siklomatik = jumlah keputusan + 1; makin besar, makin banyak jalur yang perlu diuji (di atas 10 biasanya dianggap sulit dirawat).",
                    style = MaterialTheme.typography.bodySmall)
                Text("Garis di kiri adalah lompatan; warnanya sama dengan keterangan di blok asalnya, dan garis putus-putus berarti kembali ke atas (perulangan). Blok tepat di bawah dicapai tanpa garis.",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        flow.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (flow.blocks.isEmpty()) return@Column
        // Jumps that need a line: anything but falling through to the very next block.
        val jumps = remember(flow) {
            flow.blocks.flatMap { b -> b.edges.filter { it.to != b.index + 1 || it.kind == EdgeKind.CATCH || it.kind == EdgeKind.CASE }.map { b to it } }
                .sortedBy { (b, e) -> kotlin.math.abs(e.to - b.index) }
        }
        // Each jump gets the innermost lane free over its span, so short jumps stay close to the blocks.
        val lanes = remember(jumps) {
            val used = List(LANES) { mutableListOf<IntRange>() }
            jumps.associate { (b, e) ->
                val span = minOf(b.index, e.to)..maxOf(b.index, e.to)
                val lane = used.indexOfFirst { taken -> taken.none { it.first <= span.last && span.first <= it.last } }
                if (lane >= 0) used[lane] += span
                (b.index to e) to lane
            }
        }
        val tops = remember(flow) { mutableStateMapOf<Int, Pair<Float, Float>>() }
        val gutter = 5.dp * (LANES + 2)
        Box(Modifier.fillMaxWidth().drawBehind {
            val step = 5.dp.toPx()
            val gutterPx = gutter.toPx()
            jumps.forEach { (b, e) ->
                val lane = lanes[b.index to e] ?: return@forEach
                if (lane < 0) return@forEach
                val (fromTop, fromBottom) = tops[b.index] ?: return@forEach
                val (toTop, _) = tops[e.to] ?: return@forEach
                val back = flow.isBack(b, e)
                val x = gutterPx - step * (lane + 1)
                val y1 = fromBottom - 10.dp.toPx()
                val y2 = toTop + 14.dp.toPx()
                val c = color(e.kind, back)
                val path = Path().apply { moveTo(gutterPx, y1); lineTo(x, y1); lineTo(x, y2); lineTo(gutterPx - 2, y2) }
                drawPath(path, c, style = Stroke(1.5.dp.toPx(), pathEffect = if (back) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null))
                val head = Path().apply { moveTo(gutterPx, y2); lineTo(gutterPx - 6.dp.toPx(), y2 - 4.dp.toPx()); lineTo(gutterPx - 6.dp.toPx(), y2 + 4.dp.toPx()); close() }
                drawPath(head, c)
            }
        }) {
            Column(Modifier.padding(start = gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                flow.blocks.forEach { block ->
                    Card(Modifier.fillMaxWidth().onGloballyPositioned { tops[block.index] = it.positionInParent().y to (it.positionInParent().y + it.size.height) }) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("B${block.index} · 0x${block.start.toString(16)}" + (block.exit?.let { " · keluar lewat $it" } ?: ""),
                                style = MaterialTheme.typography.labelLarge, color = if (block.exit == "throw") colors.error else colors.primary)
                            block.lines.forEach { Text(it.substringAfter("  "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            if (block.hiddenLines > 0) Text("… ${block.hiddenLines} instruksi lagi", style = MaterialTheme.typography.labelSmall)
                            block.edges.forEach { e ->
                                val back = flow.isBack(block, e)
                                Text(edgeLabel(e, back), style = MaterialTheme.typography.labelMedium, color = color(e.kind, back))
                            }
                        }
                    }
                }
            }
        }
        if (flow.truncated) Text("Diagram dipotong; method ini sangat besar.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun edgeLabel(e: FlowEdge, back: Boolean): String {
    val target = "B${e.to}"
    val text = when (e.kind) {
        EdgeKind.NEXT -> "lanjut → $target"
        EdgeKind.JUMP -> "lompat (goto) → $target"
        EdgeKind.TRUE -> "jika benar → $target"
        EdgeKind.FALSE -> "jika salah → $target"
        EdgeKind.CASE -> "case ${e.label} → $target"
        EdgeKind.DEFAULT -> "selain itu (default) → $target"
        EdgeKind.CATCH -> "bila exception ${e.label} → $target"
    }
    return if (back) "$text ↺ kembali (perulangan)" else text
}
