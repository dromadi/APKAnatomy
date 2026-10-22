package com.apkanatomy.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkanatomy.data.ANALYSIS_STEPS
import com.apkanatomy.data.HistoryEntry
import com.apkanatomy.domain.*

class MainActivity : ComponentActivity() {
    private val model: AnatomyViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colors = if (android.os.Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context) else lightColorScheme()
            MaterialTheme(colorScheme = colors) { AnatomyApp(model) }
        }
    }
}

@Composable
private fun AnatomyApp(model: AnatomyViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.inspect(it) }
    }
    BackHandler(state.report != null) { model.back() }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("APKAnatomy", style = MaterialTheme.typography.headlineLarge)
            Text("Pelajari cara aplikasi dibangun", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.report != null) OutlinedButton(onClick = model::back, enabled = !state.busy) { Text("Kembali") }
                state.report?.let { ExportMenu(it, state.baseline != null, model::keepAsBaseline, model::clearBaseline) }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Buka APK / bundle") }
                if (state.report == null) TextButton(onClick = { model.reload() }, enabled = !state.busy) { Text("Muat ulang") }
            }
            val step = state.step
            if (step != null) AnalysisProgress(step, model::cancel)
            else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            state.info?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            val report = state.report
            if (report == null) {
                // 0 = installed apps, 1 = history, 2 = audit, 3 = glossary
                var section by rememberSaveable { mutableIntStateOf(0) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                    listOf("Terinstal", "Riwayat (${state.history.size})", "Audit", "Kamus").forEachIndexed { i, label ->
                        FilterChip(selected = section == i, onClick = { section = i }, label = { Text(label) })
                    }
                }
                when (section) {
                    1 -> HistoryList(state.history, state.busy, model::openHistory, model::baselineFromHistory, model::deleteHistory, model::clearHistory)
                    2 -> AuditScreen(state.audit, state.auditProgress, state.busy, model::audit, model::cancel, model::inspect, state.auditSavedAt, state.auditDiff)
                    3 -> GlossaryScreen()
                    else -> AppList(state.apps, state.busy, model::inspect)
                }
            }
            else AnatomyReportScreen(report, model::preview, state.baseline.takeUnless { state.reportIsBaseline }, model::clearBaseline, model::resources)
        }
    }
}

/** Which step of ANALYSIS_STEPS is running, with a cancel button. */
@Composable
internal fun AnalysisProgress(step: Int, cancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Langkah ${step + 1}/${ANALYSIS_STEPS.size}: ${ANALYSIS_STEPS[step]}…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { (step + 1f) / ANALYSIS_STEPS.size }, modifier = Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = cancel) { Text("Batal") }
    }
}

@Composable
internal fun HistoryList(history: List<HistoryEntry>, busy: Boolean, open: (String) -> Unit, asBaseline: (String) -> Unit,
    delete: (String) -> Unit, clear: () -> Unit) {
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text("Laporan yang pernah dianalisis disimpan otomatis di perangkat ini (maksimal 20), tanpa daftar kode. " +
                "Buka untuk membaca lagi tanpa analisis ulang, atau jadikan pembanding untuk melihat perubahan antarversi.", style = MaterialTheme.typography.bodySmall)
        }
        if (history.isEmpty()) item { Text("Belum ada riwayat. Analisis aplikasi atau APK, lalu laporannya muncul di sini.") }
        items(history, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.titleMedium)
                    Text("${entry.packageName} · ${entry.version}", style = MaterialTheme.typography.bodySmall)
                    Text("${entry.source} · ${format.format(java.util.Date(entry.savedAt))} · ${entry.bytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { open(entry.id) }, enabled = !busy) { Text("Buka") }
                        TextButton(onClick = { asBaseline(entry.id) }, enabled = !busy) { Text("Jadikan pembanding") }
                        TextButton(onClick = { delete(entry.id) }, enabled = !busy) { Text("Hapus") }
                    }
                }
            }
        }
        if (history.isNotEmpty()) item { TextButton(onClick = clear, enabled = !busy) { Text("Hapus semua riwayat") } }
    }
}

/** Audit of all installed apps: a ranked list of what stands out, with filters; tap an app for the full analysis. */
@Composable
internal fun AuditScreen(results: List<AppAudit>?, progress: Triple<Int, Int, String>?, busy: Boolean,
    start: (Boolean) -> Unit, cancel: () -> Unit, inspect: (String) -> Unit, savedAt: Long? = null, diff: AuditDiff? = null) {
    val format = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT) }
    var includeSystem by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val ranked = remember(results) { results.orEmpty().map { it to it.reasons() }.sortedByDescending { (_, r) -> r.sumOf { it.points } } }
    val shown = remember(ranked, filter) { ranked.filter { (_, r) -> filter == null || r.any { it.filter == filter } } }
    val summary = remember(results) { results.orEmpty().auditSummary() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text("Pindai cepat semua aplikasi: izin data pribadi, SDK pelacak, debuggable, target SDK lama, komponen terbuka, dan sumber pemasangan. " +
                "Poin hanya untuk mengurutkan, bukan nilai keamanan: aplikasi peta wajar meminta lokasi. Ketuk aplikasi untuk analisis lengkap.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeSystem, { includeSystem = it }, enabled = !busy)
                Text("Sertakan aplikasi sistem", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            if (progress != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val (done, total, label) = progress
                Text(if (total == 0) "Menyiapkan…" else "Memeriksa ${done + 1}/$total: $label", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = cancel) { Text("Batal") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = { start(includeSystem) }, enabled = !busy) { Text(if (results == null) "Mulai audit" else "Audit ulang") }
                if (results != null) AuditExport(results, savedAt ?: System.currentTimeMillis(), diff)
            }
        }
        if (results != null) {
            item {
                Text("${results.size} aplikasi diperiksa", style = MaterialTheme.typography.titleMedium)
                savedAt?.let { Text("Audit ${format.format(java.util.Date(it))}", style = MaterialTheme.typography.labelSmall) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Semua") })
                    summary.forEach { (name, count) -> FilterChip(selected = filter == name, onClick = { filter = name }, enabled = count > 0, label = { Text("$name ($count)") }) }
                }
            }
            if (diff != null) item { AuditChanges(diff, format, busy, inspect) }
            if (shown.isEmpty()) item { Text("Tidak ada aplikasi yang cocok.") }
            items(shown, key = { it.first.packageName }) { (app, reasons) ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { inspect(app.packageName) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PackageIcon(app.packageName)
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text("${app.packageName} · ${app.versionName} · target ${app.targetSdk}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${reasons.sumOf { it.points }} poin", style = MaterialTheme.typography.labelLarge)
                        }
                        if (reasons.isEmpty()) Text("Tidak ada yang menonjol.", style = MaterialTheme.typography.bodySmall)
                        reasons.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
                        app.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

/** Saves the audit as CSV (spreadsheet) or HTML (browser) through the system file picker. */
@Composable
private fun AuditExport(results: List<AppAudit>, savedAt: Long, diff: AuditDiff?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf("") }
    fun save(uri: Uri?) {
        val text = pending
        if (uri == null || text.isEmpty()) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null } catch (_: Exception) { false }
            }
            Toast.makeText(context, if (saved) "Audit disimpan." else "Audit gagal disimpan.", Toast.LENGTH_SHORT).show()
        }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), ::save)
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html"), ::save)
    val name = "apkanatomy-audit-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date(savedAt))
    OutlinedButton(onClick = { pending = results.toAuditCsv(); saveCsv.launch("$name.csv") }) { Text("Simpan CSV") }
    OutlinedButton(onClick = { pending = results.toAuditHtml(savedAt, diff); saveHtml.launch("$name.html") }) { Text("Simpan HTML") }
}

/** Apps installed, removed or changed since the audit before; changes worth a look come first and are marked. */
@Composable
private fun AuditChanges(diff: AuditDiff, format: java.text.DateFormat, busy: Boolean, inspect: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Perubahan sejak ${format.format(java.util.Date(diff.since))}", style = MaterialTheme.typography.titleMedium)
            if (diff.changes.isEmpty()) Text("Tidak ada aplikasi yang dipasang, dihapus, atau berubah.", style = MaterialTheme.typography.bodySmall)
            else Text(listOf(CHANGE_NEW, CHANGE_UPDATED, CHANGE_CHANGED, CHANGE_REMOVED).mapNotNull { k -> diff.changes.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${k.lowercase()}" } }
                .joinToString() + diff.changes.count { it.attention }.takeIf { it > 0 }?.let { " · $it perlu dilihat" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
            val visible = if (all) diff.changes else diff.changes.take(8)
            visible.forEach { change ->
                val open = change.kind != CHANGE_REMOVED && !busy
                Column(Modifier.fillMaxWidth().clickable(enabled = open) { inspect(change.packageName) }.padding(vertical = 4.dp)) {
                    Text((if (change.attention) "⚠ " else "") + "${change.label} · ${change.kind.lowercase()}", style = MaterialTheme.typography.titleSmall,
                        color = if (change.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
                    change.details.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (diff.changes.size > 8) TextButton(onClick = { all = !all }) { Text(if (all) "Ringkas" else "Tampilkan semua (${diff.changes.size})") }
        }
    }
}

@Composable
internal fun GlossaryScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    val terms = remember(query) { GLOSSARY.search(query) }
    OutlinedTextField(query, { query = it }, label = { Text("Cari istilah") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Text("${GLOSSARY.size} istilah untuk membaca laporan APKAnatomy, dikelompokkan dari paket sampai privasi.", style = MaterialTheme.typography.bodySmall) }
        if (terms.isEmpty()) item { Text("Tidak ada istilah yang cocok.") }
        // While searching, results keep their relevance order; otherwise terms are grouped.
        val groups = if (query.isBlank()) terms.groupBy { it.group } else mapOf("Hasil" to terms)
        groups.forEach { (group, list) ->
            item(key = "g:$group") { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { "t:${it.term}" }) { term ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(term.term, style = MaterialTheme.typography.titleSmall)
                        Text(term.explanation, style = MaterialTheme.typography.bodySmall)
                        term.tab?.let { Text("Lihat di tab $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppList(apps: List<AppEntry>, busy: Boolean, onClick: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(apps, query) { apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
    OutlinedTextField(query, { query = it }, label = { Text("Cari nama atau package") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("${visible.size} aplikasi", modifier = Modifier.padding(vertical = 8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visible.isEmpty() && !busy) item { Text("Tidak ada aplikasi yang cocok.") }
        items(visible, key = { it.packageName }) { app ->
            Card(Modifier.fillMaxWidth().clickable(enabled = !busy) { onClick(app.packageName) }) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PackageIcon(app.packageName)
                    Column {
                        Text(app.label, style = MaterialTheme.typography.titleMedium)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                        if (app.system) Text("Aplikasi sistem", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private val iconCache = android.util.LruCache<String, ImageBitmap>(300)

@Composable
private fun PackageIcon(packageName: String) {
    val context = LocalContext.current
    var icon by remember(packageName) { mutableStateOf(iconCache.get(packageName)) }
    LaunchedEffect(packageName) {
        if (icon != null) return@LaunchedEffect
        icon = withContext(Dispatchers.IO) {
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                context.packageManager.getApplicationIcon(packageName).apply { setBounds(0, 0, 96, 96) }.draw(android.graphics.Canvas(bitmap))
                bitmap.asImageBitmap().also { iconCache.put(packageName, it) }
            } catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(40.dp)) { icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) } }
}

@Composable
private fun ExportMenu(report: AnatomyReport, hasBaseline: Boolean, keepAsBaseline: () -> Unit, clearBaseline: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }
    fun save(uri: Uri?) {
        val text = pending
        if (uri == null || text.isEmpty()) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                try { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null } catch (_: Exception) { false }
            }
            Toast.makeText(context, if (saved) "Laporan disimpan." else "Laporan gagal disimpan.", Toast.LENGTH_SHORT).show()
        }
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown"), ::save)
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), ::save)
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html"), ::save)
    val baseName = "apkanatomy-${report.packageName}-${report.version}".replace(Regex("[^A-Za-z0-9._-]"), "_")
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Aksi") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Jadikan pembanding") }, onClick = {
                expanded = false; keepAsBaseline()
                Toast.makeText(context, "Disimpan sebagai pembanding. Buka aplikasi atau APK lain, lalu lihat tab Banding.", Toast.LENGTH_LONG).show()
            })
            if (hasBaseline) DropdownMenuItem(text = { Text("Hapus pembanding") }, onClick = { expanded = false; clearBaseline() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Simpan Markdown") }, onClick = {
                expanded = false; pending = report.toMarkdown(System.currentTimeMillis()); saveMarkdown.launch("$baseName.md")
            })
            DropdownMenuItem(text = { Text("Simpan HTML (buka di browser)") }, onClick = {
                expanded = false; pending = report.toHtml(System.currentTimeMillis()); saveHtml.launch("$baseName.html")
            })
            DropdownMenuItem(text = { Text("Simpan JSON") }, onClick = {
                expanded = false; pending = report.toJson(System.currentTimeMillis()); saveJson.launch("$baseName.json")
            })
            DropdownMenuItem(text = { Text("Bagikan ringkasan") }, onClick = {
                expanded = false
                // Keep the intent well below the binder transaction limit; the saved file has no cap.
                val markdown = report.toMarkdown(System.currentTimeMillis()).let {
                    if (it.length <= 100_000) it else it.take(100_000) + "\n\n_Terpotong; simpan sebagai file untuk laporan lengkap._"
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Laporan APKAnatomy: ${report.label}").putExtra(Intent.EXTRA_TEXT, markdown)
                context.startActivity(Intent.createChooser(send, "Bagikan laporan"))
            })
        }
    }
}
