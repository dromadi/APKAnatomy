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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                state.report?.let { ExportMenu(it) }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Buka APK") }
                if (state.report == null) TextButton(onClick = { model.reload() }, enabled = !state.busy) { Text("Muat ulang") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            val report = state.report
            if (report == null) AppList(state.apps, state.busy, model::inspect)
            else AnatomyReportScreen(report, model::preview)
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
private fun ExportMenu(report: AnatomyReport) {
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
    val baseName = "apkanatomy-${report.packageName}-${report.version}".replace(Regex("[^A-Za-z0-9._-]"), "_")
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Ekspor") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Simpan Markdown") }, onClick = {
                expanded = false; pending = report.toMarkdown(System.currentTimeMillis()); saveMarkdown.launch("$baseName.md")
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
