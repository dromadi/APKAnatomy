package com.apkanatomy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            Text("Bedah struktur aplikasi", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.report != null) OutlinedButton(onClick = model::back, enabled = !state.busy) { Text("Kembali") }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("Buka APK") }
                if (state.report == null) TextButton(onClick = { model.reload() }, enabled = !state.busy) { Text("Muat ulang") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            val report = state.report
            if (report == null) AppList(state.apps, state.busy, model::inspect)
            else ReportView(report)
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
                Column(Modifier.padding(16.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    if (app.system) Text("Aplikasi sistem", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReportView(report: AnatomyReport) {
    var tab by rememberSaveable(report.packageName, report.source) { mutableIntStateOf(0) }
    var query by rememberSaveable(report.packageName, report.source) { mutableStateOf("") }
    var exportedOnly by rememberSaveable(report.packageName, report.source) { mutableStateOf(false) }
    Text(report.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
    Text(report.packageName, style = MaterialTheme.typography.bodySmall)
    Text("v${report.version} · min SDK ${report.minSdk} · target ${report.targetSdk}")
    Text(report.source, style = MaterialTheme.typography.labelSmall)
    TabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Komponen (${report.components.size})") })
        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Izin (${report.permissions.size})") })
    }
    if (tab == 0) {
        OutlinedTextField(query, { query = it }, label = { Text("Cari komponen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(exportedOnly, { exportedOnly = it })
            Text("Hanya exported", modifier = Modifier.padding(top = 14.dp))
        }
        val components = report.components.filtered(query, exportedOnly)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Text("Exported menunjukkan keterbukaan deklarasi; izin tetap dapat membatasi akses.", style = MaterialTheme.typography.bodySmall) }
            if (components.isEmpty()) item { Text("Tidak ada komponen yang cocok.") }
            items(components, key = { "${it.kind}:${it.name}" }) { component ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(component.kind.name, style = MaterialTheme.typography.labelMedium)
                        Text(component.name, style = MaterialTheme.typography.bodyMedium)
                        Text("exported=${component.exported} · enabled=${component.enabled}", style = MaterialTheme.typography.bodySmall)
                        component.permission?.let { Text("Izin akses/baca: $it", style = MaterialTheme.typography.bodySmall) }
                        component.writePermission?.let { Text("Izin tulis: $it", style = MaterialTheme.typography.bodySmall) }
                        component.authority?.let { Text("Authority: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            item { Text("Izin yang diminta pada manifest; bukan status izin yang sudah diberikan.", style = MaterialTheme.typography.bodySmall) }
            if (report.permissions.isEmpty()) item { Text("Tidak ada izin yang diminta.") }
            items(report.permissions, key = { it.name }) { permission ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(permission.name)
                        Text(permission.protection, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
