package com.apkanatomy.app

import android.content.ClipData
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.*
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun AnatomyReportScreen(report: AnatomyReport) {
    var tab by rememberSaveable(report.packageName, report.source) { mutableIntStateOf(0) }
    Text(report.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
    Text(report.packageName, style = MaterialTheme.typography.bodySmall)
    Text("v${report.version} · min SDK ${report.minSdk} · target ${report.targetSdk}")
    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
        listOf("Ringkasan", "Teknologi", "Komponen", "Izin", "Isi APK", "Kode", "Manifest", "DNA").forEachIndexed { index, label ->
            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
        }
    }
    key(report.packageName, report.source) {
        when (tab) {
            0 -> Overview(report) { tab = it }
            1 -> TechnologyScreen(report)
            2 -> ComponentsScreen(report)
            3 -> PermissionsScreen(report)
            4 -> ArchiveBrowser(report.structure)
            5 -> CodeExplorer(report.code)
            6 -> ManifestScreen(report)
            7 -> DnaScreen(report)
        }
    }
}

@Composable
private fun Overview(report: AnatomyReport, open: (Int) -> Unit) {
    val s = report.structure
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Mulai membedah", "Kenali teknologinya, jelajahi komponen, lalu telusuri file yang menyusunnya. Hasil ini berasal dari analisis statis paket; alur eksekusi belum direkam.") }
        item { LessonCard("Teknologi", s.technologies.joinToString { it.name }.ifEmpty { "Belum ada penanda framework yang dikenali. Ini tidak otomatis berarti aplikasi pure native." } +
            "\n${report.sdks.size} SDK/library dikenali" + (report.sdks.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ") { it.name }?.take(160) ?: "."), onClick = { open(1) }) }
        item { LessonCard("Kerangka", "${report.components.size} komponen · ${report.permissions.size} izin yang diminta · ${report.components.openToAllApps().size} exported tanpa izin · ${report.components.sumOf { c -> c.intentFilters.count { it.isDeepLink } }} deep link · ${report.permissions.count { sensitivePermissionLesson(it.name) != null }} izin sensitif. Pelajari titik masuk layar, kerja tanpa layar, broadcast, dan akses data.", onClick = { open(2) }) }
        item {
            val attention = remember(report) { securityNotes(report.security, report.signing, System.currentTimeMillis()).count { it.level == NoteLevel.PERHATIAN } }
            LessonCard("DNA & keamanan", "Skema tanda tangan: ${report.signing.schemes.joinToString { it.label }.ifEmpty { "tidak ditemukan" }} · " +
                "${report.signing.certificates.size} sertifikat · $attention catatan perlu perhatian. Pelajari siapa penandatangannya dan flag keamanan manifest.", onClick = { open(7) })
        }
        item { LessonCard("Manifest", report.manifestXml?.let { "${it.lines().size} baris XML hasil dekode AndroidManifest.xml biner. Lihat deklarasi lengkap, termasuk intent-filter dan meta-data." } ?: (report.manifestNote ?: "Manifest tidak tersedia."), onClick = { open(6) }) }
        item { LessonCard("Organ & isi paket", "${s.archives.size} arsip · ${s.entries.size} file · ${s.dex.size} DEX · ${s.libraries.size} library native. Ukuran isi terdaftar: ${formatSize(s.entries.filter { it.size >= 0 }.sumOf { it.size })} sebelum kompresi.", onClick = { open(4) }) }
        item { LessonCard("Telusuri kode", "${report.code.classes.size} class · ${report.code.methods.size} method terdefinisi · ${report.code.calls.size} pemanggilan langsung terbaca. Cari nama, buka instruksi, dan gunakan Find usage.", onClick = { open(5) }) }
        item { LessonCard("Panduan penelusuran", "Mulai dari nama komponen atau library yang dikenali. Catat file buktinya, kemudian gunakan tab Kode untuk menelusuri method dan pemanggilan langsung. Nama file saja belum menjelaskan fungsi atau hubungan antarbagian.") }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun TechnologyScreen(report: AnatomyReport) {
    val s = report.structure
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Petunjuk teknologi", "Pencocokan memakai nama file dan lokasi di APK. Library dapat berasal dari satu modul atau pihak ketiga; bukti ini belum memetakan seluruh arsitektur aplikasi.") }
        item { Text("SDK & library pihak ketiga", style = MaterialTheme.typography.titleMedium) }
        item { Text("Dikenali dari nama package di DEX. Kecocokan berarti library ikut dibundel, belum tentu aktif dipakai. R8 dapat mengganti nama package, jadi library yang tidak muncul belum tentu tidak ada.", style = MaterialTheme.typography.bodySmall) }
        obfuscationHint(report.code)?.let { hint -> item { LessonCard("Obfuskasi", hint) } }
        if (report.sdks.isEmpty()) item { Text("Tidak ada SDK yang cocok dengan aturan saat ini.") }
        report.sdks.groupBy { it.category }.forEach { (category, sdks) ->
            item(key = "sdk-category:$category") { Text(category, style = MaterialTheme.typography.titleSmall) }
            items(sdks, key = { "sdk:${it.name}" }) { sdk ->
                LessonCard(sdk.name, sdk.explanation +
                    (if (sdk.versions.isNotEmpty()) "\nVersi: ${sdk.versions.joinToString()}" else "") +
                    "\nBukti: ${sdk.evidence.joinToString()}" + (if (sdk.packageCount > sdk.evidence.size) " (+${sdk.packageCount - sdk.evidence.size} package lain)" else ""))
            }
        }
        if (s.libraryVersions.isNotEmpty()) item {
            LessonCard("Library tercatat (${s.libraryVersions.size})", "Gradle menyertakan file META-INF/<group>_<artifact>.version untuk banyak library Google dan AndroidX. Daftar ini lebih pasti daripada pencocokan nama package.\n\n" +
                s.libraryVersions.entries.take(200).joinToString("\n") { (artifact, version) -> "$artifact $version" } +
                (if (s.libraryVersions.size > 200) "\n… dan ${s.libraryVersions.size - 200} lainnya" else ""))
        }
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
    PrimaryScrollableTabRow(selectedTabIndex = kind + 1, edgePadding = 0.dp) {
        listOf("Semua", "Activity", "Service", "Receiver", "Provider").forEachIndexed { index, label ->
            Tab(selected = kind + 1 == index, onClick = { kind = index - 1 }, text = { Text(label) })
        }
    }
    Row { Checkbox(exportedOnly, { exportedOnly = it }); Text("Hanya exported", modifier = Modifier.padding(top = 14.dp)) }
    val open = remember(report.components) { report.components.openToAllApps().toSet() }
    val components = remember(report.components, query, exportedOnly, kind) {
        report.components.filtered(query, exportedOnly).filter { kind == -1 || it.kind.ordinal == kind }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { LessonCard("Pelajari komponen", if (kind >= 0) componentLesson(ComponentKind.entries[kind]) else "Activity: titik masuk layar. Service: kerja tanpa layar. Receiver: penerima broadcast. Provider: akses data melalui content URI.") }
        item { Text("${components.size} komponen. Exported membuka deklarasi akses dari aplikasi lain; izin tetap bisa membatasinya. Intent-filter menjelaskan pemicunya; referensi kode dari komponen belum ditautkan.", style = MaterialTheme.typography.bodySmall) }
        if (components.isEmpty()) item { Text("Tidak ada komponen yang cocok.") }
        items(components, key = { "${it.kind}:${it.name}" }) { component ->
            LessonCard(component.name, "${component.kind.name} · exported=${component.exported} · enabled=${component.enabled}" +
                (if (component in open) "\nTerbuka: aplikasi lain dapat memanggilnya tanpa izin." else "") +
                (component.role()?.let { "\nPeran: $it" } ?: "") +
                component.intentFilters.joinToString("") { "\n\n" + describeFilter(it) } +
                (component.permission?.let { "\nIzin akses/baca: $it" } ?: "") +
                (component.writePermission?.let { "\nIzin tulis: $it" } ?: "") +
                (component.authority?.let { "\nAuthority: $it" } ?: ""))
        }
    }
}

@Composable
private fun PermissionsScreen(report: AnatomyReport) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            val sensitive = report.permissions.count { sensitivePermissionLesson(it.name) != null }
            Text("$sensitive izin sensitif ditandai ⚠ dan ditampilkan lebih dulu.", style = MaterialTheme.typography.bodySmall)
        }
        item { LessonCard("Pelajari izin", "Izin memberi petunjuk kemampuan yang diminta aplikasi. Daftar ini berasal dari manifest, bukan bukti izin sudah diberikan atau kemampuan itu benar-benar digunakan.") }
        if (report.permissions.isEmpty()) item { Text("Tidak ada izin yang diminta.") }
        val sorted = report.permissions.sortedBy { sensitivePermissionLesson(it.name) == null }
        items(sorted, key = { it.name }) { permission ->
            val lesson = sensitivePermissionLesson(permission.name)
            LessonCard(if (lesson != null) "⚠ ${permission.name}" else permission.name,
                "Proteksi: ${permission.protection}" + (lesson?.let { "\nSensitif: $it" } ?: ""))
        }
    }
}

private fun describeFilter(filter: IntentFilterInfo): String = buildString {
    append("Intent-filter")
    if (filter.autoVerify) append(" (autoVerify)")
    append(":")
    if (filter.actions.isNotEmpty()) append("\nAksi: ").append(filter.actions.joinToString { it.removePrefix("android.intent.action.") })
    if (filter.categories.isNotEmpty()) append("\nKategori: ").append(filter.categories.joinToString { it.removePrefix("android.intent.category.") })
    if (filter.schemes.isNotEmpty()) append("\nSkema: ").append(filter.schemes.joinToString())
    if (filter.hosts.isNotEmpty()) append("\nHost: ").append(filter.hosts.joinToString())
    if (filter.paths.isNotEmpty()) append("\nPath: ").append(filter.paths.joinToString())
    if (filter.mimeTypes.isNotEmpty()) append("\nMIME: ").append(filter.mimeTypes.joinToString())
    filter.lesson()?.let { append("\n→ ").append(it) }
}

@Composable
private fun ManifestScreen(report: AnatomyReport) {
    val xml = report.manifestXml
    val lines = remember(xml) { xml?.trimEnd()?.lines().orEmpty() }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            LessonCard("Pelajari manifest", "Di dalam APK, AndroidManifest.xml disimpan sebagai XML biner. APKAnatomy mendekodenya sendiri tanpa PackageManager, sehingga intent-filter, meta-data, dan atribut lain ikut terlihat. Referensi resource tampil sebagai ID seperti @0x7f0a0001 karena resources.arsc belum dibaca.")
        }
        if (xml == null) item { Text(report.manifestNote ?: "Manifest tidak tersedia.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
        else item { TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("AndroidManifest.xml", xml))) } }) { Text("Salin XML") } }
        itemsIndexed(lines) { index, line ->
            Row {
                Text("${index + 1}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.End, modifier = Modifier.width(36.dp).padding(end = 8.dp))
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

private val schemeLessons = listOf(
    SignatureScheme.V1 to "Tanda tangan JAR di META-INF. Melindungi tiap file di dalam ZIP, tetapi tidak seluruh arsip.",
    SignatureScheme.V2 to "Blok tanda tangan sebelum direktori ZIP yang melindungi seluruh isi arsip (Android 7+).",
    SignatureScheme.V3 to "Seperti v2, ditambah rentang SDK dan riwayat rotasi kunci (Android 9+).",
    SignatureScheme.V31 to "Blok v3 terpisah agar rotasi kunci hanya berlaku mulai Android 13, sementara perangkat lama tetap memakai kunci lama.",
    SignatureScheme.V4 to "Tanda tangan di file .idsig terpisah untuk instalasi bertahap (incremental, mis. lewat adb).",
)

@Composable
private fun DnaScreen(report: AnatomyReport) {
    val signing = report.signing
    val notes = remember(report) { securityNotes(report.security, signing, System.currentTimeMillis()) }
    val date = remember { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Pelajari DNA aplikasi", "Tanda tangan membuktikan APK berasal dari pemegang kunci yang sama dan isinya tidak diubah. Pembaruan aplikasi hanya diterima bila ditandatangani kunci yang sama atau penerusnya. Halaman ini membaca tanda tangan; verifikasi digest dilakukan Android saat instalasi.") }
        item { Text("Catatan audit", style = MaterialTheme.typography.titleMedium) }
        items(notes) { note -> NoteCard(note) }
        item { Text("Skema tanda tangan", style = MaterialTheme.typography.titleMedium) }
        items(schemeLessons) { (scheme, lesson) ->
            val status = when {
                scheme in signing.schemes -> "✓ Ada"
                scheme == SignatureScheme.V4 && !signing.v4Checked -> "Tidak dapat diperiksa dari satu file APK"
                else -> "Tidak ada"
            }
            LessonCard("${scheme.label}: $status", lesson)
        }
        if (SignatureScheme.SOURCE_STAMP in signing.schemes) item { LessonCard("Source stamp: ✓ Ada", "Cap sumber dari distributor (mis. Google Play) untuk menandai asal APK.") }
        item { Text("Sertifikat penandatangan", style = MaterialTheme.typography.titleMedium) }
        if (signing.certificates.isEmpty()) item { Text("Tidak ada sertifikat yang terbaca.") }
        items(signing.certificates, key = { it.sha256 }) { cert ->
            LessonCard(cert.subject, buildString {
                appendLine("Ditemukan di: ${cert.foundIn.joinToString()}")
                appendLine("Penerbit: ${cert.issuer}${if (cert.issuer == cert.subject) " (self-signed, lazim untuk APK)" else ""}")
                appendLine("Berlaku: ${date.format(java.util.Date(cert.notBefore))} s.d. ${date.format(java.util.Date(cert.notAfter))}")
                appendLine("Kunci: ${cert.keyAlgorithm}${cert.keySize?.let { " $it bit" } ?: ""} · Algoritma: ${cert.signatureAlgorithm}")
                appendLine("Serial: ${cert.serialHex}")
                appendLine("SHA-256: ${cert.sha256}")
                appendLine("SHA-1: ${cert.sha1}")
                append("MD5: ${cert.md5}")
            })
        }
        if (signing.otherBlocks.isNotEmpty()) item { LessonCard("Isi lain APK Signing Block", signing.otherBlocks.joinToString("\n")) }
        items(signing.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun NoteCard(note: SecurityNote) {
    val colors = MaterialTheme.colorScheme
    val (container, label) = when (note.level) {
        NoteLevel.PERHATIAN -> colors.errorContainer to "Perlu perhatian"
        NoteLevel.INFO -> colors.secondaryContainer to "Info"
        NoteLevel.BAIK -> colors.surfaceVariant to "Baik"
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$label · ${note.title}", style = MaterialTheme.typography.titleSmall)
            Text(note.explanation, style = MaterialTheme.typography.bodySmall)
        }
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
        PrimaryScrollableTabRow(selectedTabIndex = archiveIndex, edgePadding = 0.dp) {
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
