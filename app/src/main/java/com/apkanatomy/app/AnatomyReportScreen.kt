package com.apkanatomy.app

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.*
import java.util.Locale

@Composable
internal fun AnatomyReportScreen(report: AnatomyReport) {
    var tab by rememberSaveable(report.packageName, report.source) { mutableIntStateOf(0) }
    Text(report.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
    Text(report.packageName, style = MaterialTheme.typography.bodySmall)
    Text("v${report.version} · min SDK ${report.minSdk} · target ${report.targetSdk}")
    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
        listOf("Ringkasan", "Teknologi", "Komponen", "Izin", "Isi APK", "Kode").forEachIndexed { index, label ->
            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
        }
    }
    key(report.packageName, report.source) {
        when (tab) {
            0 -> Overview(report) { tab = it }
            1 -> TechnologyScreen(report.structure)
            2 -> ComponentsScreen(report)
            3 -> PermissionsScreen(report)
            4 -> ArchiveBrowser(report.structure)
            5 -> CodeExplorer(report.code)
        }
    }
}

@Composable
private fun Overview(report: AnatomyReport, open: (Int) -> Unit) {
    val s = report.structure
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Mulai membedah", "Kenali teknologinya, jelajahi komponen, lalu telusuri file yang menyusunnya. Hasil ini berasal dari analisis statis paket; alur eksekusi belum direkam.") }
        item { LessonCard("Teknologi", s.technologies.joinToString { it.name }.ifEmpty { "Belum ada penanda yang dikenali. Ini tidak otomatis berarti aplikasi pure native." }, onClick = { open(1) }) }
        item { LessonCard("Kerangka", "${report.components.size} komponen · ${report.permissions.size} izin yang diminta. Pelajari titik masuk layar, kerja tanpa layar, broadcast, dan akses data.", onClick = { open(2) }) }
        item { LessonCard("Organ & isi paket", "${s.archives.size} arsip · ${s.entries.size} file · ${s.dex.size} DEX · ${s.libraries.size} library native. Ukuran isi terdaftar: ${formatSize(s.entries.filter { it.size >= 0 }.sumOf { it.size })} sebelum kompresi.", onClick = { open(4) }) }
        item { LessonCard("Telusuri kode", "${report.code.classes.size} class · ${report.code.methods.size} method terdefinisi · ${report.code.calls.size} pemanggilan langsung terbaca. Cari nama, buka instruksi, dan gunakan Find usage.", onClick = { open(5) }) }
        item { LessonCard("Panduan penelusuran", "Mulai dari nama komponen atau library yang dikenali. Catat file buktinya, kemudian gunakan tab Kode untuk menelusuri method dan pemanggilan langsung. Nama file saja belum menjelaskan fungsi atau hubungan antarbagian.") }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun TechnologyScreen(s: PackageStructure) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Petunjuk teknologi", "Pencocokan memakai nama file dan lokasi di APK. Library dapat berasal dari satu modul atau pihak ketiga; bukti ini belum memetakan seluruh arsitektur aplikasi.") }
        if (s.technologies.isEmpty()) item { Text("Tidak ada penanda teknologi yang dikenali oleh aturan saat ini.") }
        items(s.technologies, key = { it.name }) { technology ->
            LessonCard(technology.name, technology.explanation + "\n\nBukti:\n" + technology.evidence.joinToString("\n"))
        }
        item { Text("DEX: bytecode Android", style = MaterialTheme.typography.titleMedium) }
        item { Text("Referensi method mencakup method aplikasi dan dependensi. Penjumlahan lintas DEX bukan jumlah method unik. Header tidak membuktikan perilaku kode.", style = MaterialTheme.typography.bodySmall) }
        if (s.dex.isEmpty()) item { Text("Tidak ada classes*.dex yang ditemukan pada arsip terbaca.") }
        items(s.dex, key = { "${it.archive}:${it.path}" }) { dex ->
            LessonCard("${dex.archive}/${dex.path}", dex.note ?: "DEX ${dex.version}\n${dex.classDefinitions} definisi class\n${dex.methodReferences} referensi method")
        }
        item { Text("Native library & ABI", style = MaterialTheme.typography.titleMedium) }
        item { Text("ABI menunjukkan arsitektur CPU tujuan library, bukan CPU yang pasti sedang digunakan. Daftar ini menggabungkan paket utama dan split yang terbaca.", style = MaterialTheme.typography.bodySmall) }
        if (s.libraries.isEmpty()) item { Text("Tidak ada library native di lib/<ABI>/*.so pada arsip terbaca.") }
        items(s.libraries, key = { "${it.archive}:${it.path}" }) { lib ->
            LessonCard(lib.path.substringAfterLast('/'), "ABI: ${lib.abi} · ${formatSize(lib.size)}\n${lib.archive}/${lib.path}")
        }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun ComponentsScreen(report: AnatomyReport) {
    var query by rememberSaveable { mutableStateOf("") }
    var exportedOnly by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableIntStateOf(-1) }
    OutlinedTextField(query, { query = it }, label = { Text("Cari komponen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    ScrollableTabRow(selectedTabIndex = kind + 1, edgePadding = 0.dp) {
        listOf("Semua", "Activity", "Service", "Receiver", "Provider").forEachIndexed { index, label ->
            Tab(selected = kind + 1 == index, onClick = { kind = index - 1 }, text = { Text(label) })
        }
    }
    Row { Checkbox(exportedOnly, { exportedOnly = it }); Text("Hanya exported", modifier = Modifier.padding(top = 14.dp)) }
    val components = remember(report.components, query, exportedOnly, kind) {
        report.components.filtered(query, exportedOnly).filter { kind == -1 || it.kind.ordinal == kind }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { LessonCard("Pelajari komponen", if (kind >= 0) componentLesson(ComponentKind.entries[kind]) else "Activity: titik masuk layar. Service: kerja tanpa layar. Receiver: penerima broadcast. Provider: akses data melalui content URI.") }
        item { Text("${components.size} komponen. Exported membuka deklarasi akses dari aplikasi lain; izin tetap bisa membatasinya. Intent-filter dan referensi kode belum ditampilkan.", style = MaterialTheme.typography.bodySmall) }
        if (components.isEmpty()) item { Text("Tidak ada komponen yang cocok.") }
        items(components, key = { "${it.kind}:${it.name}" }) { component ->
            LessonCard(component.name, "${component.kind.name} · exported=${component.exported} · enabled=${component.enabled}" +
                (component.permission?.let { "\nIzin akses/baca: $it" } ?: "") +
                (component.writePermission?.let { "\nIzin tulis: $it" } ?: "") +
                (component.authority?.let { "\nAuthority: $it" } ?: ""))
        }
    }
}

@Composable
private fun PermissionsScreen(report: AnatomyReport) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Pelajari izin", "Izin memberi petunjuk kemampuan yang diminta aplikasi. Daftar ini berasal dari manifest, bukan bukti izin sudah diberikan atau kemampuan itu benar-benar digunakan.") }
        if (report.permissions.isEmpty()) item { Text("Tidak ada izin yang diminta.") }
        items(report.permissions, key = { it.name }) { permission -> LessonCard(permission.name, "Proteksi: ${permission.protection}") }
    }
}

@Composable
private fun ArchiveBrowser(s: PackageStructure) {
    var archiveIndex by rememberSaveable { mutableIntStateOf(0) }
    var prefix by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<ArchiveEntry?>(null) }
    val archive = s.archives.getOrNull(archiveIndex)
    if (s.archives.size > 1) {
        ScrollableTabRow(selectedTabIndex = archiveIndex, edgePadding = 0.dp) {
            s.archives.forEachIndexed { index, name -> Tab(archiveIndex == index, { archiveIndex = index; prefix = ""; query = "" }, text = { Text(name) }) }
        }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Cari path file dalam arsip") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { prefix = ""; query = "" }) { Text("Root") }
        if (prefix.isNotEmpty()) TextButton(onClick = { prefix = prefix.trimEnd('/').substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } }) { Text("Naik") }
    }
    Text("${archive ?: "Arsip tidak tersedia"}/${prefix}", style = MaterialTheme.typography.bodySmall)
    val entries = remember(s.entries, archive, prefix, query) {
        s.entries.filter { it.archive == archive && it.path.startsWith(prefix) && it.path.contains(query, true) }
    }
    val folders = remember(entries, prefix, query) {
        if (query.isNotEmpty()) emptyList() else entries.map { it.path.removePrefix(prefix) }.filter { '/' in it }.map { it.substringBefore('/') }.distinct().sorted()
    }
    val files = remember(entries, prefix, query) { if (query.isNotEmpty()) entries else entries.filter { '/' !in it.path.removePrefix(prefix) } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Text("${entries.size} file dalam cakupan. Ketuk folder untuk menjelajah, atau file untuk melihat metadata dan perannya.", style = MaterialTheme.typography.bodySmall) }
        items(folders, key = { "folder:$it" }) { folder -> LessonCard("Folder: $folder/", "Buka isi folder", onClick = { prefix += "$folder/" }) }
        items(files.take(250), key = { it.id }) { entry ->
            LessonCard(if (query.isEmpty()) entry.path.substringAfterLast('/') else entry.path,
                "Isi: ${formatSize(entry.size)} · di ZIP: ${formatSize(entry.compressedSize)}", onClick = { selected = entry })
        }
        if (files.size > 250) item { Text("Menampilkan 250 dari ${files.size} file. Persempit pencarian untuk menemukan file lain.") }
        if (entries.isEmpty()) item { Text("Tidak ada file yang cocok pada cakupan ini.") }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Pelajari file") },
            text = { SelectionContainer { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${entry.archive}/${entry.path}")
                Text("Ukuran isi: ${formatSize(entry.size)}\nUkuran tersimpan: ${formatSize(entry.compressedSize)}")
                Text(explainEntry(entry.path))
                Text("Tahap ini menampilkan metadata file; pembaca isi file dan penelusuran kode menyusul.", style = MaterialTheme.typography.bodySmall)
            } } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Tutup") } })
    }
}

@Composable
private fun LessonCard(title: String, body: String, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(body, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun formatSize(size: Long): String = when {
    size < 0 -> "tidak diketahui"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", size / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024))
}
