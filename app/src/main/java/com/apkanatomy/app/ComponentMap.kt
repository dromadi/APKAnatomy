package com.apkanatomy.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.*

private const val COLUMNS = 3
private const val MAX_NODES = 45

/**
 * Which component starts which: boxes in rows by distance from the launcher, arrows for starts found in
 * code, and the list of links below (tap one to open the method that does it).
 */
@Composable
internal fun ComponentMapCard(report: AnatomyReport, openMethod: (String) -> Unit, indexed: Set<String>) {
    var libraries by rememberSaveable { mutableStateOf(false) }
    val map = remember(report, libraries) { report.screenMap(libraries) }
    val colors = MaterialTheme.colorScheme
    fun fill(kind: ComponentKind?) = when (kind) {
        ComponentKind.ACTIVITY -> colors.primaryContainer
        ComponentKind.SERVICE -> colors.tertiaryContainer
        ComponentKind.RECEIVER, ComponentKind.PROVIDER -> colors.secondaryContainer
        null -> colors.surface
    }
    fun ink(kind: ComponentKind?) = when (kind) {
        ComponentKind.ACTIVITY -> colors.onPrimaryContainer
        ComponentKind.SERVICE -> colors.onTertiaryContainer
        ComponentKind.RECEIVER, ComponentKind.PROVIDER -> colors.onSecondaryContainer
        null -> colors.onSurface
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Peta komponen", style = MaterialTheme.typography.titleMedium)
            Text("Panah berarti kode membuat Intent ke komponen itu (startActivity, startService, PendingIntent notifikasi, dan sejenisnya). " +
                "Baris atas adalah titik awal: layar peluncur dan kode yang tidak dimulai komponen lain. Navigasi di dalam satu Activity (fragment, Compose) tidak terlihat.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(libraries, { libraries = it })
                Text("Sertakan tautan antar-library" + if (!libraries && map.hiddenLibraryEdges > 0) " (${map.hiddenLibraryEdges} disembunyikan)" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (map.edges.isEmpty()) {
                Text("Tidak ada komponen yang dimulai dari kode yang terbaca. Aplikasi satu-Activity (mis. Compose atau Navigation) berpindah layar tanpa Intent.",
                    style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            val nodes = map.nodes.take(MAX_NODES)
            val measurer = rememberTextMeasurer()
            val nameStyle = MaterialTheme.typography.labelMedium
            val kindStyle = MaterialTheme.typography.labelSmall
            // Rows: one per level, wrapping every COLUMNS boxes.
            val rows = remember(nodes) { nodes.groupBy { it.level }.toSortedMap().values.flatMap { it.chunked(COLUMNS) } }
            val boxH = 54.dp
            val rowGap = 30.dp
            Canvas(Modifier.fillMaxWidth().height(boxH * rows.size + rowGap * (rows.size - 1))) {
                val gap = 8.dp.toPx()
                val w = (size.width - gap * (COLUMNS - 1)) / COLUMNS
                val h = boxH.toPx()
                val rowH = h + rowGap.toPx()
                val at = HashMap<String, Offset>()
                rows.forEachIndexed { r, row ->
                    val shift = (size.width - (row.size * w + (row.size - 1) * gap)) / 2
                    row.forEachIndexed { c, node -> at[node.type] = Offset(shift + c * (w + gap), r * rowH) }
                }
                map.edges.forEach { e ->
                    val from = at[e.from] ?: return@forEach
                    val to = at[e.to] ?: return@forEach
                    val down = to.y > from.y
                    val start = Offset(from.x + w / 2, if (down) from.y + h else from.y)
                    val end = Offset(to.x + w / 2, if (down) to.y else to.y + h)
                    val color = if (down) colors.outline else colors.secondary
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        if (down) cubicTo(start.x, start.y + rowGap.toPx(), end.x, end.y - rowGap.toPx(), end.x, end.y)
                        else cubicTo(start.x + w * 0.4f, start.y - rowGap.toPx(), end.x + w * 0.4f, end.y + rowGap.toPx(), end.x, end.y)
                    }
                    drawPath(path, color, style = Stroke(1.5.dp.toPx(), pathEffect = if (down) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                    val dir = if (down) 1f else -1f
                    val head = Path().apply { moveTo(end.x, end.y); lineTo(end.x - 4.dp.toPx(), end.y - dir * 7.dp.toPx()); lineTo(end.x + 4.dp.toPx(), end.y - dir * 7.dp.toPx()); close() }
                    drawPath(head, color)
                }
                nodes.forEach { node ->
                    val o = at[node.type] ?: return@forEach
                    drawRoundRect(fill(node.kind), o, Size(w, h), CornerRadius(10.dp.toPx()))
                    if (node.launcher) drawRoundRect(colors.primary, o, Size(w, h), CornerRadius(10.dp.toPx()), style = Stroke(2.dp.toPx()))
                    else if (node.kind == null) drawRoundRect(colors.outlineVariant, o, Size(w, h), CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
                    val name = measurer.measure(node.name, nameStyle.copy(color = ink(node.kind)), overflow = TextOverflow.Ellipsis, maxLines = 1,
                        constraints = Constraints(maxWidth = (w - 12.dp.toPx()).toInt().coerceAtLeast(1)))
                    val kind = measurer.measure(kindLabel(node), kindStyle.copy(color = ink(node.kind)), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = (w - 12.dp.toPx()).toInt().coerceAtLeast(1)))
                    drawText(name, topLeft = Offset(o.x + 6.dp.toPx(), o.y + 8.dp.toPx()))
                    drawText(kind, topLeft = Offset(o.x + 6.dp.toPx(), o.y + h - kind.size.height - 7.dp.toPx()))
                }
            }
            if (map.nodes.size > MAX_NODES) Text("Diagram menampilkan $MAX_NODES dari ${map.nodes.size} kotak; daftar di bawah lengkap.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf<Pair<String, ComponentKind?>>("Activity" to ComponentKind.ACTIVITY, "Service" to ComponentKind.SERVICE, "Receiver" to ComponentKind.RECEIVER, "Kode lain" to null).forEach { (label, kind) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(12.dp).background(fill(kind), RoundedCornerShape(3.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(3.dp)))
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val names = map.nodes.associate { it.type to it.name }
            map.edges.forEach { e ->
                val via = e.via.firstOrNull { it in indexed }
                Text("${names[e.from]} → ${names[e.to]}" + (via?.let { " · ${prettyMethod(it).substringAfterLast('.').substringBefore('(')}()" } ?: "") +
                    if (e.via.size > 1) " (${e.via.size} tempat)" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (via != null) colors.primary else colors.onSurface,
                    modifier = Modifier.fillMaxWidth().then(if (via != null) Modifier.clickable { openMethod(via) } else Modifier).padding(vertical = 4.dp))
            }
        }
    }
}

private fun kindLabel(node: ScreenNode) = when (node.kind) {
    ComponentKind.ACTIVITY -> if (node.launcher) "Activity · peluncur" else "Activity"
    ComponentKind.SERVICE -> "Service"
    ComponentKind.RECEIVER -> "Receiver"
    ComponentKind.PROVIDER -> "Provider"
    null -> "kode"
} + if (node.exported && !node.launcher) " · exported" else ""
