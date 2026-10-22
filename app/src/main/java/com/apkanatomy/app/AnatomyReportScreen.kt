package com.apkanatomy.app

import android.content.ClipData
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.drawText
import androidx.activity.compose.BackHandler
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
import com.apkanatomy.data.HistoryStore
import com.apkanatomy.domain.*
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun AnatomyReportScreen(report: AnatomyReport, loadPreview: suspend (ArchiveEntry) -> FilePreview,
    baseline: AnatomyReport? = null, clearBaseline: () -> Unit = {},
    loadResources: suspend () -> ResourceListing = { ResourceListing(emptyList(), "Penjelajah resource tidak tersedia di sini.") }) {
    var selectedTab by rememberSaveable(report.packageName, report.source) { mutableIntStateOf(0) }
    val tabs = listOf("Ringkasan", "Cari", "Privasi", "Perilaku", "Teknologi", "Komponen", "Izin", "Kemampuan", "Isi APK", "Kode", "Manifest", "DNA", "Kulit", "Jejak") + listOfNotNull(baseline?.let { "Banding" })
    // The Banding tab disappears when the baseline is cleared.
    val tab = selectedTab.coerceAtMost(tabs.lastIndex)
    // A method to open in the Kode tab, set when another tab links to code.
    var pendingMethod by remember(report) { mutableStateOf<String?>(null) }
    val openMethod: (String) -> Unit = { id -> pendingMethod = id; selectedTab = tabs.indexOf("Kode") }
    var pendingClass by remember(report) { mutableStateOf<String?>(null) }
    val openClass: (String) -> Unit = { id -> pendingClass = id; selectedTab = tabs.indexOf("Kode") }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val icon = remember(report.iconPng) {
            report.iconPng?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
        Column {
            Text(report.label, style = MaterialTheme.typography.titleLarge)
            Text(report.packageName, style = MaterialTheme.typography.bodySmall)
            Text((if (report.version == "—") "versi tidak tercantum" else "v${report.version}") + " · min SDK ${report.minSdk} · target ${report.targetSdk}")
            if (report.source.startsWith(HistoryStore.FROM_HISTORY)) Text(report.source, style = MaterialTheme.typography.labelSmall)
        }
    }
    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
        tabs.forEachIndexed { index, label ->
            Tab(selected = tab == index, onClick = { selectedTab = index }, text = { Text(label) })
        }
    }
    key(report.packageName, report.source) {
        when (tabs[tab]) {
            "Ringkasan" -> Overview(report, baseline) { name -> selectedTab = tabs.indexOf(name).coerceAtLeast(0) }
            "Cari" -> SearchScreen(report, { name -> selectedTab = tabs.indexOf(name).coerceAtLeast(0) }, openMethod, openClass)
            "Privasi" -> PrivacyScreen(report)
            "Teknologi" -> TechnologyScreen(report)
            "Komponen" -> ComponentsScreen(report, openClass)
            "Izin" -> PermissionsScreen(report)
            "Kemampuan" -> CapabilitiesScreen(report.capabilities)
            "Perilaku" -> BehaviorScreen(report.behaviors, report.code.methods.mapTo(HashSet()) { it.id }, openMethod, remember(report.native) { report.native.importFindings() })
            "Isi APK" -> ArchiveBrowser(report.structure, loadPreview)
            "Kode" -> CodeExplorer(report.code, pendingMethod, pendingClass, remember(report.components) { report.components.entryTypes() }) { pendingMethod = null; pendingClass = null }
            "Manifest" -> ManifestScreen(report)
            "DNA" -> DnaScreen(report)
            "Kulit" -> SkinScreen(report, loadResources)
            "Jejak" -> TracesScreen(report.traces, report.code.methods.mapTo(HashSet()) { it.id }, openMethod)
            "Banding" -> baseline?.let { CompareScreen(it, report, clearBaseline) }
        }
    }
}

/** One search box over every section of the report; a hit opens its tab, or the class or method in Kode. */
@Composable
private fun SearchScreen(report: AnatomyReport, openTab: (String) -> Unit, openMethod: (String) -> Unit, openClass: (String) -> Unit) {
    var query by rememberSaveable(report.packageName) { mutableStateOf("") }
    val results = remember(report, query) { report.searchAll(query) }
    val indexed = remember(report.code) { report.code.methods.mapTo(HashSet()) { it.id } }
    OutlinedTextField(query, { query = it }, label = { Text("Cari di seluruh laporan") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (query.trim().length < 2) item {
            LessonCard("Cari apa saja", "Nama komponen, izin, SDK, host, perilaku, class, method, string literal, baris manifest, catatan DNA, atau nama file. " +
                "Contoh: \"login\", \"firebase\", \"CAMERA\", \"http://\".")
        } else if (results.isEmpty()) item { Text("Tidak ada yang cocok dengan \"${query.trim()}\".", style = MaterialTheme.typography.bodyMedium) }
        results.forEach { (section, hits) ->
            item(key = "s:$section") { Text("$section (${hits.size}${if (hits.size >= 30) "+" else ""})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(hits.withIndex().toList(), key = { "h:$section:${it.index}" }) { (_, hit) ->
                val classId = hit.classId
                val methodId = hit.methodId
                val action = when {
                    classId != null -> { { openClass(classId) } }
                    methodId != null && methodId in indexed && hit.tab == "Kode" -> { { openMethod(methodId) } }
                    else -> { { openTab(hit.tab) } }
                }
                Card(Modifier.fillMaxWidth().clickable(onClick = action)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(hit.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Overview(report: AnatomyReport, baseline: AnatomyReport?, open: (String) -> Unit) {
    val s = report.structure
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (baseline != null) item {
            val diff = remember(report, baseline) { compareReports(baseline, report, System.currentTimeMillis()) }
            val count = diff.sumOf { it.changes.size }
            val flagged = diff.sumOf { section -> section.changes.count { it.attention } }
            LessonCard("Perubahan dari ${baseline.version}", if (count == 0) "Tidak ada perbedaan dengan laporan pembanding (${baseline.label} ${baseline.version})."
                else "$count perubahan dibanding ${baseline.label} ${baseline.version}" + (if (flagged > 0) ", $flagged perlu dicermati" else "") +
                    ": " + diff.joinToString { "${it.title} (${it.changes.size})" } + ".", onClick = { open("Banding") })
        }
        item { LessonCard("Mulai membedah", "Kenali teknologinya, jelajahi komponen, lalu telusuri file yang menyusunnya. Hasil ini berasal dari analisis statis paket; alur eksekusi belum direkam.") }
        item {
            val privacy = remember(report) { report.privacyProfile() }
            LessonCard("Privasi", privacy.summary, onClick = { open("Privasi") })
        }
        item {
            val b = report.behaviors
            LessonCard("Perilaku", if (b.findings.isEmpty()) "Tidak ada pemanggilan API sensitif yang dikenali di kode yang terbaca." else
                "${b.findings.size} perilaku dari ${b.categories.size} kelompok (${b.findings.count { it.level == NoteLevel.PERHATIAN }} perlu perhatian): " +
                    b.findings.take(4).joinToString { it.title.lowercase() } + if (b.findings.size > 4) ", …" else ".", onClick = { open("Perilaku") })
        }
        item { LessonCard("Teknologi", s.technologies.joinToString { it.name }.ifEmpty { "Belum ada penanda framework yang dikenali. Ini tidak otomatis berarti aplikasi pure native." } +
            "\n${report.sdks.size} SDK/library dikenali" + (report.sdks.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ") { it.name }?.take(160) ?: "."), onClick = { open("Teknologi") }) }
        item { LessonCard("Kerangka", "${report.components.size} komponen · ${report.permissions.size} izin yang diminta · ${report.components.openToAllApps().size} exported tanpa izin · ${report.components.sumOf { c -> c.intentFilters.count { it.isDeepLink } }} deep link · ${report.permissions.count { sensitivePermissionLesson(it.name) != null }} izin sensitif. Pelajari titik masuk layar, kerja tanpa layar, broadcast, dan akses data.", onClick = { open("Komponen") }) }
        item {
            val attention = remember(report) { report.notes(System.currentTimeMillis()).count { it.level == NoteLevel.PERHATIAN } }
            LessonCard("DNA & keamanan", "Skema tanda tangan: ${report.signing.schemes.joinToString { it.label }.ifEmpty { "tidak ditemukan" }} · " +
                "${report.signing.certificates.size} sertifikat · $attention catatan perlu perhatian. Pelajari siapa penandatangannya dan flag keamanan manifest.", onClick = { open("DNA") })
        }
        item {
            val t = report.traces
            LessonCard("Jejak", "${t.hosts.size} host/endpoint · ${t.secrets.size} kandidat kunci atau token (${t.secrets.count { it.level == NoteLevel.PERHATIAN }} perlu perhatian). Lihat server yang dihubungi dan string yang mirip rahasia.", onClick = { open("Jejak") })
        }
        item { LessonCard("Manifest", report.manifestXml?.let { "${it.lines().size} baris XML hasil dekode AndroidManifest.xml biner. Lihat deklarasi lengkap, termasuk intent-filter dan meta-data." } ?: (report.manifestNote ?: "Manifest tidak tersedia."), onClick = { open("Manifest") }) }
        item {
            val largest = remember(s) { sizeBreakdown(s.entries).firstOrNull() }
            LessonCard("Kulit & ukuran", (largest?.let { "Bagian terbesar: ${it.category.label} (${formatSize(it.size)}). " } ?: "") +
                "Lihat proporsi ukuran, font, model ML, web bundle, dan file menarik lain.", onClick = { open("Kulit") })
        }
        item { LessonCard("Organ & isi paket", "${s.archives.size} arsip · ${s.entries.size} file · ${s.dex.size} DEX · ${s.libraries.size} library native. Ukuran isi terdaftar: ${formatSize(s.entries.filter { it.size >= 0 }.sumOf { it.size })} sebelum kompresi.", onClick = { open("Isi APK") }) }
        item { LessonCard("Telusuri kode", "${report.code.classes.size} class · ${report.code.methods.size} method terdefinisi · ${report.code.calls.size} pemanggilan langsung terbaca. Cari nama, buka instruksi, dan gunakan Find usage.", onClick = { open("Kode") }) }
        item { LessonCard("Panduan penelusuran", "Mulai dari nama komponen atau library yang dikenali. Catat file buktinya, kemudian gunakan tab Kode untuk menelusuri method dan pemanggilan langsung. Nama file saja belum menjelaskan fungsi atau hubungan antarbagian.") }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

/** Treemap of class counts per package group: where the code in the APK comes from. */
@Composable
private fun CodeMapCard(blocks: List<CodeBlock>, total: Int) {
    val colors = MaterialTheme.colorScheme
    val fill = mapOf(CodeOwner.APP to colors.primary, CodeOwner.PLATFORM to colors.tertiary, CodeOwner.LIBRARY to colors.secondary, CodeOwner.OBFUSCATED to colors.outline)
    val ink = mapOf(CodeOwner.APP to colors.onPrimary, CodeOwner.PLATFORM to colors.onTertiary, CodeOwner.LIBRARY to colors.onSecondary, CodeOwner.OBFUSCATED to colors.surface)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Peta kode", style = MaterialTheme.typography.titleMedium)
            Text("Luas setiap kotak sebanding dengan jumlah class ($total class di semua DEX). Kode aplikasi sering hanya sebagian kecil; sisanya library.",
                style = MaterialTheme.typography.bodySmall)
            val rects = remember(blocks) { squarify(blocks.map { it.classes.toDouble() }) }
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(240.dp)) {
                val gap = 2.dp.toPx()
                blocks.zip(rects).forEach { (block, r) ->
                    val left = r.x * size.width; val top = r.y * size.height
                    val w = r.w * size.width - gap; val h = r.h * size.height - gap
                    if (w <= 0f || h <= 0f) return@forEach
                    drawRect(fill.getValue(block.owner), androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Size(w, h))
                    val text = measurer.measure(block.sdk ?: block.name, labelStyle.copy(color = ink.getValue(block.owner)),
                        constraints = androidx.compose.ui.unit.Constraints(maxWidth = (w - 8).toInt().coerceAtLeast(0)), maxLines = 2)
                    if (text.size.height + 6 < h && w > 40) drawText(text, topLeft = androidx.compose.ui.geometry.Offset(left + 4, top + 3))
                }
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CodeOwner.entries.filter { o -> blocks.any { it.owner == o } }.forEach { owner ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(10.dp).background(fill.getValue(owner)))
                        Text(owner.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val share = blocks.filter { it.owner == CodeOwner.APP }.sumOf { it.classes } * 100 / total.coerceAtLeast(1)
            Text("Kode aplikasi sendiri: sekitar $share%. Terbesar: " + blocks.take(5).joinToString { "${it.sdk ?: it.name} (${it.classes})" },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TechnologyScreen(report: AnatomyReport) {
    val s = report.structure
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Petunjuk teknologi", "Pencocokan memakai nama file dan lokasi di APK. Library dapat berasal dari satu modul atau pihak ketiga; bukti ini belum memetakan seluruh arsitektur aplikasi.") }
        val blocks = report.codeMap()
        if (blocks.isNotEmpty()) item(key = "code-map") { CodeMapCard(blocks, report.code.definedClasses) }
        item { Text("Proteksi & pengemasan", style = MaterialTheme.typography.titleMedium) }
        val protections = report.protections()
        if (protections.isEmpty()) item { Text("Tidak ada packer, kode tersembunyi, penyamaran nama, atau pemeriksaan lingkungan yang dikenali.", style = MaterialTheme.typography.bodyMedium) }
        items(protections, key = { "protection:${it.name}" }) { p -> LessonCard("${p.kind}: ${p.name}", p.explanation + "\nBukti: " + p.evidence.joinToString()) }
        item { Text("SDK & library pihak ketiga", style = MaterialTheme.typography.titleMedium) }
        item { Text("Dikenali dari nama package di DEX. Kecocokan berarti library ikut dibundel, belum tentu aktif dipakai. R8 dapat mengganti nama package, jadi library yang tidak muncul belum tentu tidak ada.", style = MaterialTheme.typography.bodySmall) }
        if (report.sdks.isEmpty()) item { Text("Tidak ada SDK yang cocok dengan aturan saat ini.", style = MaterialTheme.typography.bodyMedium) }
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
        if (s.technologies.isEmpty()) item { Text("Tidak ada penanda teknologi yang dikenali oleh aturan saat ini.", style = MaterialTheme.typography.bodyMedium) }
        items(s.technologies, key = { it.name }) { technology ->
            LessonCard(technology.name, technology.explanation + "\n\nBukti:\n" + technology.evidence.joinToString("\n"))
        }
        item { Text("DEX: bytecode Android", style = MaterialTheme.typography.titleMedium) }
        item { Text("Referensi method mencakup method aplikasi dan dependensi. Penjumlahan lintas DEX bukan jumlah method unik. Header tidak membuktikan perilaku kode.", style = MaterialTheme.typography.bodySmall) }
        if (s.dex.isEmpty()) item { Text("Tidak ada classes*.dex yang ditemukan pada arsip terbaca.", style = MaterialTheme.typography.bodyMedium) }
        items(s.dex, key = { "${it.archive}:${it.path}" }) { dex ->
            LessonCard("${dex.archive}/${dex.path}", dex.note ?: "DEX ${dex.version}\n${dex.classDefinitions} definisi class\n${dex.methodReferences} referensi method")
        }
        item { Text("Native library & ABI", style = MaterialTheme.typography.titleMedium) }
        item { Text("ABI menunjukkan arsitektur CPU tujuan library, bukan CPU yang pasti sedang digunakan. Daftar ini menggabungkan paket utama dan split yang terbaca.", style = MaterialTheme.typography.bodySmall) }
        report.native.pageSizeNote()?.let { note -> item { NoteCard(note) } }
        if (report.native.libraries.isEmpty()) item { Text("Tidak ada library native di lib/<ABI>/*.so pada arsip terbaca.", style = MaterialTheme.typography.bodyMedium) }
        else item {
            val declared = remember(report.code) { report.code.methods.count { "native" in it.access } }
            val exported = report.native.libraries.sumOf { it.jniMethods.size }
            LessonCard("JNI: jembatan Java ↔ native", "Metode Java bertanda native dijalankan oleh kode C/C++. Pendaftarannya lewat nama simbol Java_paket_Kelas_metode (terlihat di bawah) atau lewat RegisterNatives di dalam JNI_OnLoad (tidak terlihat dari simbol).\n" +
                "$declared metode native terlihat di indeks kode · $exported fungsi JNI diekspor dengan nama.")
        }
        items(report.native.libraries, key = { "${it.archive}:${it.path}" }) { lib ->
            LessonCard("${lib.abi}/${lib.path.substringAfterLast('/')}", buildString {
                append(lib.machine?.let { "$it · ${lib.bits}-bit" } ?: "ELF tidak terbaca").append(" · ").append(formatSize(lib.size))
                lib.minLoadAlign?.let { append("\nSegmen LOAD rata ${it / 1024} KB").append(if (it >= 16_384) " ✓ siap 16 KB" else if (lib.is64Bit) " ✗ belum 16 KB" else " (32-bit, tidak wajib 16 KB)") }
                if (lib.stored) append("\nTersimpan tanpa kompresi, offset ZIP ${lib.zipDataOffset}").append(if (lib.zipAligned16k == true) " (rata 16 KB)" else " (tidak rata 16 KB)")
                lib.soname?.let { append("\nSONAME: $it") }
                if (lib.needed.isNotEmpty()) append("\nBergantung pada: ").append(lib.needed.joinToString())
                if (lib.hasJniOnLoad) append("\nPunya JNI_OnLoad: dapat mendaftarkan metode native sendiri saat dimuat.")
                if (lib.jniMethods.isNotEmpty()) append("\nMetode JNI: ").append(lib.jniMethods.take(12).joinToString()).append(if (lib.jniMethods.size > 12) " … (+${lib.jniMethods.size - 12})" else "")
                if (lib.notableImports.isNotEmpty()) append("\nImpor penting: ").append(lib.notableImports.joinToString { "$it (${NATIVE_IMPORTS[it]?.first?.lowercase()})" })
                if (lib.hasDebugInfo) append("\nMembawa info debug (.debug_*): ukuran lebih besar dan lebih mudah dianalisis.")
                else if (lib.hasSymbolTable) append("\nTidak di-strip: tabel simbol lengkap (.symtab) ikut terbawa.")
                lib.note?.let { append("\n").append(it) }
                append("\n").append(lib.archive)
            })
        }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun ComponentsScreen(report: AnatomyReport, openClass: (String) -> Unit) {
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
    // Component class name → indexed class, to open it in the Kode tab.
    val classByName = remember(report.code) { report.code.classes.groupBy { it.displayName }.mapValues { it.value.first() } }
    val components = remember(report.components, query, exportedOnly, kind) {
        report.components.filtered(query, exportedOnly).filter { kind == -1 || it.kind.ordinal == kind }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { LessonCard("Pelajari komponen", if (kind >= 0) componentLesson(ComponentKind.entries[kind]) else "Activity: titik masuk layar. Service: kerja tanpa layar. Receiver: penerima broadcast. Provider: akses data melalui content URI.") }
        item { Text("${components.size} komponen. Exported membuka deklarasi akses dari aplikasi lain; izin tetap bisa membatasinya. Intent-filter menjelaskan pemicunya. Ketuk “Buka kode” untuk melihat method komponen, misalnya onCreate atau onReceive.", style = MaterialTheme.typography.bodySmall) }
        if (components.isEmpty()) item { Text("Tidak ada komponen yang cocok.", style = MaterialTheme.typography.bodyMedium) }
        items(components, key = { "${it.kind}:${it.name}" }) { component ->
            LessonCard(component.name, "${component.kind.name} · exported=${component.exported} · enabled=${component.enabled}" +
                (if (component in open) "\nTerbuka: aplikasi lain dapat memanggilnya tanpa izin." else "") +
                (component.role()?.let { "\nPeran: $it" } ?: "") +
                component.intentFilters.joinToString("") { "\n\n" + describeFilter(it) } +
                (component.permission?.let { "\nIzin akses/baca: $it" } ?: "") +
                (component.writePermission?.let { "\nIzin tulis: $it" } ?: "") +
                (component.authority?.let { "\nAuthority: $it" } ?: ""))
            val cls = classByName[component.name]
            if (cls != null) TextButton(onClick = { openClass(cls.id) }) { Text("Buka kode ${component.name.substringAfterLast('.')} →") }
            else Text(if (report.code.warnings.isEmpty()) "Class ini tidak ada di DEX yang terbaca; bisa berasal dari split lain atau framework." else "Class ini tidak ada di indeks kode (indeks dibatasi atau dari riwayat).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
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
        if (report.permissions.isEmpty()) item { Text("Tidak ada izin yang diminta.", style = MaterialTheme.typography.bodyMedium) }
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
    val notes = remember(report) { report.notes(System.currentTimeMillis()) }
    val date = remember { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Pelajari DNA aplikasi", "Tanda tangan membuktikan APK berasal dari pemegang kunci yang sama dan isinya tidak diubah. Pembaruan aplikasi hanya diterima bila ditandatangani kunci yang sama atau penerusnya. Halaman ini membaca tanda tangan; verifikasi digest dilakukan Android saat instalasi.") }
        item { Text("Catatan audit", style = MaterialTheme.typography.titleMedium) }
        items(notes) { note -> NoteCard(note) }
        item { Text("Network Security Config", style = MaterialTheme.typography.titleMedium) }
        item {
            val nsc = report.networkSecurity
            LessonCard(nsc?.file ?: "Tidak ada", if (nsc == null) {
                (report.resources?.note ?: "Aplikasi tidak memakai Network Security Config, sehingga berlaku aturan bawaan untuk target SDK ${report.targetSdk}: " +
                    (if (report.targetSdk >= 24) "hanya CA sistem" else "CA sistem dan CA pengguna") + ".")
            } else buildString {
                append("Konfigurasi jaringan per domain: kapan HTTP diizinkan, CA mana yang dipercaya, dan kunci yang dipin. Nama file dapat dipendekkan R8, jadi dicari lewat resources.arsc.")
                nsc.base?.let { append("\n\nbase-config: ").append(describeScope(it)) }
                nsc.domains.forEach { append("\n\n").append(it.domains.joinToString()).append(": ").append(describeScope(it)) }
                nsc.debugOverrides?.let { append("\n\ndebug-overrides: ").append(describeScope(it)) }
            })
        }
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
        if (signing.certificates.isEmpty()) item { Text("Tidak ada sertifikat yang terbaca.", style = MaterialTheme.typography.bodyMedium) }
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

/** Browse resources.arsc: filter by type, search names and values, and see every configuration's value. */
@Composable
private fun ResourceExplorer(loadResources: suspend () -> ResourceListing, back: () -> Unit) {
    BackHandler(onBack = back)
    var listing by remember { mutableStateOf<ResourceListing?>(null) }
    LaunchedEffect(Unit) { listing = try { loadResources() } catch (e: Exception) { ResourceListing(emptyList(), e.message ?: "Resource gagal dibaca.") } }
    var type by rememberSaveable { mutableStateOf<String?>("string") }
    var query by rememberSaveable { mutableStateOf("") }
    TextButton(onClick = back) { Text("Kembali ke Kulit") }
    val current = listing
    if (current == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val types = remember(current) { current.types }
    val effectiveType = type?.takeIf { it in types }
    val shown = remember(current, effectiveType, query) { current.items.filterBy(effectiveType, query) }
    OutlinedTextField(query, { query = it }, label = { Text("Cari nama, ID, atau isi") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = effectiveType == null, onClick = { type = null }, label = { Text("Semua") })
        types.forEach { t -> FilterChip(selected = effectiveType == t, onClick = { type = t }, label = { Text(t) }) }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Text("${shown.size} resource. Setiap resource punya ID 0x7f… dan nilai per konfigurasi (bahasa, mode malam, ukuran layar); " +
                "Android memilih yang paling cocok dengan perangkat.", style = MaterialTheme.typography.bodySmall)
        }
        current.note?.let { item { LessonCard("Catatan", it) } }
        items(shown.take(300), key = { it.id }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Text(item.idHex, style = MaterialTheme.typography.labelSmall)
                    SelectionContainer {
                        Text(item.values.take(40).joinToString("\n") { (config, value) -> "${describeConfig(config)}: $value" } +
                            if (item.values.size > 40) "\n… ${item.values.size - 40} konfigurasi lain" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (shown.size > 300) item { Text("Menampilkan 300 hasil; persempit pencarian.", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SkinScreen(report: AnatomyReport, loadResources: suspend () -> ResourceListing) {
    var exploring by rememberSaveable(report.packageName) { mutableStateOf(false) }
    if (exploring) { ResourceExplorer(loadResources) { exploring = false }; return }
    val s = report.structure
    val slices = remember(s) { sizeBreakdown(s.entries) }
    val groups = remember(s) { notableFiles(s.entries) }
    val total = slices.sumOf { it.size }.coerceAtLeast(1)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Pelajari kulit aplikasi", "Proporsi ukuran menunjukkan ke mana \"berat\" APK pergi: kode, library native, resource, atau aset. Ukuran dihitung sebelum kompresi dari semua arsip yang terbaca; kolom ZIP menunjukkan ukuran tersimpan.") }
        item { Text("Proporsi ukuran (${formatSize(total)} sebelum kompresi)", style = MaterialTheme.typography.titleMedium) }
        if (slices.isEmpty()) item { Text("Tidak ada entri dengan ukuran yang diketahui.", style = MaterialTheme.typography.bodyMedium) }
        items(slices, key = { it.category.name }) { slice -> SizeBar(slice, total) }
        report.resources?.let { res ->
            item(key = "resources") {
                LessonCard("Resource & bahasa", if (res.count == 0) res.note.orEmpty() else
                    "${res.count} resource terdaftar di resources.arsc.\n" +
                    res.typeCounts.entries.take(12).joinToString(" · ") { "${it.key} ${it.value}" } +
                    "\n\n${res.locales.size} bahasa/wilayah" + (if (res.locales.isNotEmpty()) ": " + res.locales.joinToString() else "") +
                    ". Terjemahan menunjukkan pasar yang dituju; sebagian bisa berasal dari library.")
                if (res.count > 0) TextButton(onClick = { exploring = true }) { Text("Jelajahi resource →") }
            }
        }
        groups.forEach { group ->
            item(key = "group:${group.title}") {
                LessonCard("${group.title} (${group.files.size})", group.explanation + "\n\n" +
                    group.files.take(20).joinToString("\n") { "${it.path} · ${formatSize(it.size)}" } +
                    (if (group.files.size > 20) "\n… dan ${group.files.size - 20} lainnya" else ""))
            }
        }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
}

/** One labelled bar of a part-to-whole breakdown; the value is printed, so color carries no meaning. */
@Composable
private fun SizeBar(slice: SizeSlice, total: Long) {
    val share = slice.size.toFloat() / total
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(slice.category.label, style = MaterialTheme.typography.bodyMedium)
            Text(String.format(Locale.ROOT, "%.1f%% · %s", share * 100, formatSize(slice.size)), style = MaterialTheme.typography.bodyMedium)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0.01f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
        }
        Text("${slice.files} file · di ZIP ${formatSize(slice.compressedSize)} · ${slice.category.lesson}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompareScreen(old: AnatomyReport, new: AnatomyReport, clearBaseline: () -> Unit) {
    val sections = remember(old, new) { compareReports(old, new, System.currentTimeMillis()) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            LessonCard("Pelajari perubahan", "Lama: ${old.label} ${old.version} (${old.source})\nBaru: ${new.label} ${new.version} (${new.source})\n\n" +
                "Membandingkan dua versi menunjukkan apa yang dibawa sebuah update: izin, komponen, SDK, sertifikat, dan ukuran. Tanda + ditambah, − dihapus, ~ berubah; ⚑ perlu diperhatikan.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Perbandingan", diffToMarkdown(old, new, sections)))) } }) { Text("Salin Markdown") }
                TextButton(onClick = clearBaseline) { Text("Hapus pembanding") }
            }
        }
        if (sections.isEmpty()) item { Text("Tidak ada perbedaan yang terdeteksi.", style = MaterialTheme.typography.bodyMedium) }
        sections.forEach { section ->
            item(key = "diff:${section.title}") { Text(section.title, style = MaterialTheme.typography.titleMedium) }
            items(section.changes) { change ->
                Text("${change.kind.symbol} ${change.text}" + if (change.attention) "  ⚑" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (change.attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun PrivacyScreen(report: AnatomyReport) {
    val p = remember(report) { report.privacyProfile() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Ringkasan privasi", p.summary + "\n\nDisusun dari isi APK (SDK yang dibundel, izin yang diminta, alamat di string). Belum tentu semuanya aktif: izin runtime harus disetujui pengguna, dan SDK bisa saja tidak dipanggil. Bandingkan dengan bagian Keamanan Data di Google Play.") }
        item { Text("Pelacak (${p.trackers.size})", style = MaterialTheme.typography.titleMedium) }
        if (p.trackers.isEmpty()) item { Text("Tidak ada SDK iklan, analitik, atribusi, atau pelaporan crash yang dikenali.", style = MaterialTheme.typography.bodyMedium) }
        items(p.trackers, key = { "tracker:${it.name}" }) { t -> LessonCard(t.name, "Tujuan: ${t.purpose}\nBukti: ${t.evidence.joinToString()}") }
        item { Text("Data yang dapat diakses (${p.dataAccess.size})", style = MaterialTheme.typography.titleMedium) }
        if (p.dataAccess.isEmpty()) item { Text("Tidak meminta izin untuk kategori data pribadi yang umum.", style = MaterialTheme.typography.bodyMedium) }
        items(p.dataAccess, key = { "data:${it.category}" }) { d ->
            LessonCard(d.category, d.explanation + "\n" + (if (d.runtimePrompt) "Pengguna ditanya saat aplikasi memintanya." else "Tidak muncul sebagai dialog izin biasa (diberikan saat instalasi atau lewat Pengaturan).") +
                "\nIzin: " + d.permissions.joinToString { it.substringAfterLast('.') })
        }
        if (p.trackerHosts.isNotEmpty()) item { LessonCard("Alamat layanan pelacak (${p.trackerHosts.size})", p.trackerHosts.joinToString("\n") + "\nDitemukan di string aplikasi; lihat tab Jejak untuk lokasinya.") }
    }
}

@Composable
private fun CapabilitiesScreen(caps: Capabilities) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { LessonCard("Pelajari kemampuan", "Selain izin, manifest menyatakan perangkat keras yang dibutuhkan, aplikasi lain yang ingin dilihat, pekerjaan latar depan, library sistem, dan aturan backup. Google Play memakai fitur wajib untuk menyaring perangkat yang boleh memasang aplikasi.") }
        caps.securityNotes().forEach { note -> item { NoteCard(note) } }
        item {
            LessonCard("Perangkat keras & fitur (${caps.features.size})", if (caps.features.isEmpty() && caps.glEsVersion == null) "Tidak menyatakan uses-feature." else
                caps.features.joinToString("\n") { f -> "${f.name} · ${if (f.required) "wajib" else "opsional"}" + (featureLesson(f.name)?.let { " — $it" } ?: "") } +
                    (caps.glEsVersion?.let { "\nOpenGL ES $it" } ?: ""))
        }
        item {
            LessonCard("Foreground service (${caps.foregroundServices.size})", if (caps.foregroundServices.isEmpty()) "Tidak ada service dengan foregroundServiceType." else
                "Sejak Android 14, setiap foreground service wajib menyatakan jenisnya; notifikasi tetap tampil selama berjalan.\n\n" +
                    caps.foregroundServices.joinToString("\n\n") { s -> s.service + "\n" + s.types.joinToString("\n") { t -> "• $t" + (foregroundTypeLesson(t)?.let { " — $it" } ?: "") } })
        }
        item {
            val q = caps.queries
            LessonCard("Package visibility (queries)", if (q.isEmpty) "Tidak ada <queries>. Sejak Android 11 aplikasi hanya dapat melihat aplikasi lain yang dinyatakan di sini (atau dengan QUERY_ALL_PACKAGES)." else
                "Sejak Android 11 aplikasi hanya dapat melihat aplikasi lain yang dinyatakan di sini. Daftar ini menunjukkan aplikasi atau layanan yang ingin diajak berinteraksi.\n" +
                    listOfNotNull(q.packages.takeIf { it.isNotEmpty() }?.let { "\nPackage: " + it.joinToString() },
                        q.intents.takeIf { it.isNotEmpty() }?.let { "\nIntent: " + it.joinToString("; ") },
                        q.providers.takeIf { it.isNotEmpty() }?.let { "\nProvider: " + it.joinToString() }).joinToString(""))
        }
        item {
            LessonCard("Aturan backup", if (caps.backupRules.isEmpty()) "Tidak ada file aturan backup; bila backup diizinkan, semua data aplikasi ikut kecuali yang dikecualikan sistem." else
                caps.backupRuleFiles.joinToString() + "\n\n" + caps.backupRules.joinToString("\n") { r -> "${r.section}: ${if (r.include) "sertakan" else "kecualikan"} ${r.domain}/${r.path}" })
        }
        if (caps.libraries.isNotEmpty()) item { LessonCard("Library sistem (uses-library)", caps.libraries.joinToString("\n") { (n, req) -> "$n · ${if (req) "wajib" else "opsional"}" }) }
        if (caps.grantUriProviders.isNotEmpty()) item { LessonCard("Provider dengan grantUriPermissions", caps.grantUriProviders.joinToString() + "\nProvider ini dapat memberi akses sementara ke URI tertentu kepada aplikasi lain, misalnya saat berbagi file.") }
        items(caps.notes) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun TracesScreen(traces: TraceReport, indexed: Set<String>, openMethod: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            LessonCard("Pelajari jejak", "String di DEX, resource, meta-data manifest, dan file assets/res/raw dipindai untuk alamat server dan string yang mirip kunci atau token. " +
                "Ini petunjuk, bukan bukti: alamat bisa milik library, dan kunci bisa saja tidak aktif atau memang publik. Nilai rahasia hanya ditampilkan tersamar. ${traces.scannedStrings} string dipindai.")
        }
        item { Text("Kandidat kunci & token (${traces.secrets.size})", style = MaterialTheme.typography.titleMedium) }
        if (traces.secrets.isEmpty()) item { Text("Tidak ada string yang cocok dengan pola kunci atau token yang dikenali.", style = MaterialTheme.typography.bodyMedium) }
        items(traces.secrets) { secret ->
            NoteCard(SecurityNote(secret.level, "${secret.type}: ${secret.masked}", secret.lesson + "\nDitemukan di: " + secret.locations.joinToString()))
            UsedBy(secret.usedBy, indexed, openMethod)
        }
        item { Text("Host & endpoint (${traces.hosts.size})", style = MaterialTheme.typography.titleMedium) }
        if (traces.hosts.isEmpty()) item { Text("Tidak ada URL yang ditemukan.", style = MaterialTheme.typography.bodyMedium) }
        items(traces.hosts, key = { "host:${it.host}" }) { host ->
            LessonCard(host.host + (host.service?.let { " · $it" } ?: ""), buildString {
                if (host.local) append("Alamat lokal/jaringan pengembangan; sering sisa saat development.\n")
                if (host.cleartext) append("Ada URL http:// atau ws:// tanpa enkripsi.\n")
                append("${host.count} kemunculan. Contoh: ").append(host.examples.joinToString())
                append("\nDitemukan di: ").append(host.locations.joinToString())
            })
            UsedBy(host.usedBy, indexed, openMethod)
        }
        items(traces.notes) { LessonCard("Hasil sebagian", it) }
    }
}

@Composable
private fun BehaviorScreen(behaviors: BehaviorReport, indexed: Set<String>, openMethod: (String) -> Unit, native: List<NativeImportFinding> = emptyList()) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            LessonCard("Pelajari perilaku", "Izin hanya menyatakan apa yang boleh; kode menunjukkan apa yang dipanggil. Setiap instruksi DEX yang terbaca " +
                "(${behaviors.scannedInstructions} instruksi) diperiksa untuk pemanggilan API sensitif dan pola berisiko. Ini bukti bahwa kode ada, " +
                "bukan bahwa kode dijalankan: banyak temuan berasal dari library yang fiturnya tidak dipakai. Kode yang dimuat saat berjalan tidak terlihat.")
        }
        if (behaviors.findings.isEmpty()) item { Text("Tidak ada pemanggilan API sensitif yang dikenali di kode DEX.", style = MaterialTheme.typography.bodyMedium) }
        behaviors.findings.groupBy { it.category }.forEach { (category, findings) ->
            item(key = "cat:$category") { Text(category, style = MaterialTheme.typography.titleMedium) }
            items(findings, key = { "b:${it.id}" }) { finding ->
                NoteCard(SecurityNote(finding.level, finding.title, finding.lesson + "\n\nAPI: " + finding.apis.joinToString() +
                    "\n${finding.count} tempat" + if (finding.inAppPackage > 0) ", ${finding.inAppPackage} di package aplikasi sendiri." else ", semuanya di luar package aplikasi (library)."))
                UsedBy(finding.usedBy, indexed, openMethod, label = "Contoh lokasi:")
            }
        }
        if (native.isNotEmpty()) {
            item(key = "native-title") { Text("Dari library native", style = MaterialTheme.typography.titleMedium) }
            item(key = "native-lesson") { Text("Fungsi yang diimpor file .so dari libc/libdl. Ini menunjukkan kemampuan kode native, yang tidak terlihat di DEX.", style = MaterialTheme.typography.bodySmall) }
            items(native, key = { "native:${it.title}" }) { f ->
                NoteCard(SecurityNote(NoteLevel.INFO, f.title, f.lesson + "\n\nFungsi: " + f.symbols.joinToString() + "\nLibrary: " + f.libraries.joinToString()))
            }
        }
        items(behaviors.notes) { LessonCard("Hasil sebagian", it) }
    }
}

/** Links to the methods that load a string, opening them in the Kode tab; methods outside the code index are listed without a link. */
@Composable
private fun UsedBy(methodIds: List<String>, indexed: Set<String>, openMethod: (String) -> Unit, label: String = "Dipakai di (const-string):") {
    if (methodIds.isEmpty()) return
    Column(Modifier.padding(start = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        methodIds.forEach { id ->
            if (id in indexed) TextButton(onClick = { openMethod(id) }) { Text("→ ${prettyMethod(id)}", style = MaterialTheme.typography.bodySmall) }
            else Text("${prettyMethod(id)} (di luar indeks kode)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
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
private fun ArchiveBrowser(s: PackageStructure, loadPreview: suspend (ArchiveEntry) -> FilePreview) {
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
        if (entries.isEmpty()) item { Text("Tidak ada file yang cocok pada cakupan ini.", style = MaterialTheme.typography.bodyMedium) }
        items(s.warnings) { LessonCard("Hasil sebagian", it) }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("Pelajari file") },
            text = { SelectionContainer { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${entry.archive}/${entry.path}")
                Text("Ukuran isi: ${formatSize(entry.size)}\nUkuran tersimpan: ${formatSize(entry.compressedSize)}")
                Text(explainEntry(entry.path))
                HorizontalDivider()
                FilePreviewView(entry, loadPreview)
            } } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Tutup") } })
    }
}

@Composable
private fun FilePreviewView(entry: ArchiveEntry, loadPreview: suspend (ArchiveEntry) -> FilePreview) {
    var preview by remember(entry) { mutableStateOf<FilePreview?>(null) }
    LaunchedEffect(entry) { preview = loadPreview(entry) }
    when (val p = preview) {
        null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("Membaca isi file…") }
        is FilePreview.Text -> {
            // Laying out hundreds of KiB in one Text stalls the UI; the copy action is the way to the full text.
            val shown = p.text.length > MAX_PREVIEW_CHARS
            Text(p.kind + (if (p.truncated) " · dipotong pada 256 KiB" else "") + (if (shown) " · ditampilkan $MAX_PREVIEW_CHARS karakter pertama" else ""),
                style = MaterialTheme.typography.labelMedium)
            Text(p.text.take(MAX_PREVIEW_CHARS), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        is FilePreview.Image -> {
            val bitmap = remember(p) { decodeSampled(p.bytes, 1024)?.asImageBitmap() }
            Text("Gambar ${p.format}" + (bitmap?.let { " · ${it.width}×${it.height} px (diperkecil bila perlu)" } ?: ""), style = MaterialTheme.typography.labelMedium)
            if (bitmap != null) Image(bitmap, contentDescription = entry.path, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp))
            else Text("Gambar tidak dapat didekode oleh perangkat.")
        }
        is FilePreview.Hex -> {
            Text(p.note, style = MaterialTheme.typography.labelMedium)
            Text(p.dump, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        is FilePreview.Unavailable -> Text(p.reason, color = MaterialTheme.colorScheme.error)
    }
}

private const val MAX_PREVIEW_CHARS = 32_000

/** Decodes with a power-of-two sample size so the longer side stays near [maxSide]. */
private fun decodeSampled(bytes: ByteArray, maxSide: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
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

internal fun formatSize(size: Long): String = when {
    size < 0 -> "tidak diketahui"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", size / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", size / (1024.0 * 1024))
}
