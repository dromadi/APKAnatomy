package com.apkanatomy.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.*

@Composable
internal fun CodeExplorer(index: CodeIndex, openMethodId: String? = null, openClassId: String? = null,
    /** Manifest component types, where call paths start. */
    entryTypes: Map<String, ComponentKind> = emptyMap(),
    /** Reads a method's control-flow graph from the DEX; reports opened from history cannot. */
    loadFlow: suspend (String) -> ControlFlow = { ControlFlow(emptyList(), false, "Diagram alur tidak tersedia di sini.") },
    onOpened: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedClassId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMethodId by rememberSaveable { mutableStateOf<String?>(null) }
    var methodHistory by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    fun backMethod() {
        selectedMethodId = methodHistory.lastOrNull()
        methodHistory = methodHistory.dropLast(1)
    }
    // 0 = class, 1 = method, 2 = string literal
    var mode by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(openMethodId, openClassId) {
        if (openMethodId != null) { selectedClassId = null; selectedMethodId = openMethodId; onOpened() }
        else if (openClassId != null) { selectedMethodId = null; methodHistory = emptyList(); selectedClassId = openClassId; onOpened() }
    }
    val methodById = remember(index) { index.methods.associateBy { it.id } }
    val methodsByDescriptor = remember(index) { index.methods.groupBy { it.descriptor } }
    val graph = remember(index) { CallGraph(index) }
    val entryPoints = remember(graph, entryTypes) { graph.entryPoints(entryTypes) }
    val selectedClass = remember(index, selectedClassId) { index.classes.find { it.id == selectedClassId } }
    val selectedMethod = methodById[selectedMethodId]
    BackHandler(selectedClass != null || selectedMethod != null) {
        if (selectedMethod != null) backMethod() else selectedClassId = null
    }
    if (selectedMethod != null) {
        MethodDetail(index, selectedMethod, methodById, methodsByDescriptor, graph, entryPoints, loadFlow,
            back = { backMethod() }, open = {
                methodHistory = (methodHistory + selectedMethod.id).takeLast(20)
                selectedMethodId = it
            })
        return
    }
    if (selectedClass != null) {
        val methods = remember(index, selectedClass) { index.methods.filter { it.owner == selectedClass.type && it.archive == selectedClass.archive && it.dex == selectedClass.dex } }
        TextButton(onClick = { selectedClassId = null }) { Text("Kembali ke pencarian") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                CodeCard(selectedClass.displayName, "${selectedClass.archive}/${selectedClass.dex}\nSuperclass: ${selectedClass.superType ?: "—"}\nInterface: ${selectedClass.interfaces.joinToString().ifEmpty { "—" }}" +
                    "\nFile sumber: " + (selectedClass.realSourceFile() ?: "tidak ada (dihapus atau diganti R8)") +
                    (selectedClass.sourceHint()?.let { "\nNama class ini disamarkan, tetapi atribut SourceFile masih menyimpan nama file aslinya: $it. Aplikasi sering mempertahankannya agar laporan crash tetap terbaca." } ?: ""))
            }
            item { Text("${methods.size} method terdefinisi dalam cakupan indeks. Nama dapat disamarkan oleh obfuscation.") }
            items(methods.take(500), key = { it.id }) { method -> CodeCard(method.descriptor, method.access, onClick = { selectedMethodId = method.id }) }
            if (methods.size > 500) item { Text("Daftar dibatasi 500 method; gunakan pencarian method untuk menemukan yang lain.") }
        }
        return
    }
    OutlinedTextField(query, { query = it }, label = { Text(listOf("Cari nama class", "Cari nama / signature method", "Cari isi string literal")[mode]) },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Class", "Method", "String").forEachIndexed { i, label -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(label) }) }
    }
    val classes = remember(index, query) { index.classes.filter { it.displayName.contains(query, true) || (query.length >= 2 && it.realSourceFile()?.contains(query, true) == true) } }
    val hinted = remember(index) { index.classes.count { it.sourceHint() != null } }
    val methods = remember(index, query) { index.methods.filter { it.descriptor.contains(query, true) } }
    val strings = remember(index, query) { if (query.length < 2) emptyList() else index.strings.filter { it.value.contains(query, true) } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { CodeCard("Mulai menelusuri", "Cari nama class, signature method, atau string literal (mis. URL, pesan error, nama endpoint). Buka method untuk melihat instruksi DEX, pemanggilan keluar, dan Find usage.") }
        item { Text("${index.classes.size} class · ${index.methods.size} method · ${index.calls.size} pemanggilan langsung. Hasil bukan kode Java hasil dekompilasi.", style = MaterialTheme.typography.bodySmall) }
        if (hinted > 0) item { CodeCard("Petunjuk nama asli", "$hinted class bernama tersamar masih menyimpan nama file sumbernya (atribut SourceFile). Cari nama file seperti “ViewModel.kt” untuk menemukannya; nama itu muncul di setiap hasil class.") }
        items(index.warnings) { CodeCard("Cakupan terbatas", it) }
        if (mode == 2) {
            item { Text(if (query.length < 2) "Ketik minimal 2 huruf untuk mencari di ${index.strings.size} string literal." else "${strings.size} string literal cocok", style = MaterialTheme.typography.bodyMedium) }
            items(strings.take(250).withIndex().toList(), key = { "s${it.index}" }) { (_, use) ->
                CodeCard(use.value, "Dimuat oleh ${prettyMethod(use.methodId)}", onClick = { selectedMethodId = use.methodId })
            }
            if (strings.size > 250) item { Text("Menampilkan 250 hasil; persempit pencarian.") }
        } else if (mode == 1) {
            item { Text("${methods.size} hasil method") }
            items(methods.take(250), key = { it.id }) { method -> CodeCard(method.descriptor, "${method.archive}/${method.dex}", onClick = { selectedMethodId = method.id }) }
            if (methods.size > 250) item { Text("Menampilkan 250 hasil; persempit pencarian.") }
        } else {
            item { Text("${classes.size} hasil class") }
            items(classes.take(250), key = { it.id }) { cls -> CodeCard(cls.displayName, "${cls.archive}/${cls.dex}" + (cls.sourceHint()?.let { " · dari $it" } ?: ""), onClick = { selectedClassId = cls.id }) }
            if (classes.size > 250) item { Text("Menampilkan 250 hasil; persempit pencarian.") }
        }
    }
}

@Composable
private fun MethodDetail(index: CodeIndex, method: CodeMethod,
    byId: Map<String, CodeMethod>, byDescriptor: Map<String, List<CodeMethod>>,
    graph: CallGraph, entryPoints: Map<String, String>, loadFlow: suspend (String) -> ControlFlow,
    back: () -> Unit, open: (String) -> Unit) {
    var tab by rememberSaveable(method.id) { mutableIntStateOf(0) }
    var showPseudo by rememberSaveable(method.id) { mutableStateOf(method.pseudo.isNotEmpty()) }
    TextButton(onClick = back) { Text("Kembali") }
    PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
        listOf("Instruksi", "Alur", "Memanggil", "Find usage", "Jalur").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
    }
    val outgoing = remember(index, method) { index.callsFrom(method.id) }
    val incoming = remember(index, method) { index.callersOf(method.descriptor) }
    // Only computed when the tab is open; a search can visit thousands of methods.
    val paths = remember(index, method, tab == 4) { if (tab == 4) graph.pathsTo(method.id, entryPoints.keys) else null }
    var flow by remember(method.id) { mutableStateOf<ControlFlow?>(null) }
    LaunchedEffect(method.id, tab == 1) { if (tab == 1 && flow == null) flow = loadFlow(method.id) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { CodeCard(method.descriptor, "${method.access}\n${method.archive}/${method.dex}") }
        if (index.warnings.isNotEmpty()) item { CodeCard("Indeks sebagian", "Ada batas atau kegagalan pemindaian. Hasil Find usage tidak mencakup seluruh aplikasi; lihat peringatan pada pencarian kode.") }
        when (tab) {
            0 -> {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = showPseudo, onClick = { showPseudo = true }, enabled = method.pseudo.isNotEmpty(), label = { Text("Kode semu") })
                        FilterChip(selected = !showPseudo, onClick = { showPseudo = false }, label = { Text("Instruksi DEX") })
                    }
                }
                item {
                    Text(if (showPseudo) "Kode semu mirip Java, diterjemahkan per instruksi: register tetap bernama v0, p0 (parameter), atau this; percabangan tetap berupa goto. " +
                        "Untuk dibaca, bukan dikompilasi; nama variabel dan tipe asli sudah hilang saat kompilasi."
                        else "Instruksi ditampilkan dengan offset dalam code unit DEX; ketuk satu instruksi untuk penjelasannya. $REGISTER_NOTE", style = MaterialTheme.typography.bodySmall)
                }
                if (!method.hasCode) item { Text("Tidak ada implementasi DEX untuk method ini; bisa berupa abstract atau native.") }
                else if (method.pseudo.isEmpty()) item { Text("Kode semu tidak dibuat untuk method ini (batas tampilan tercapai); lihat instruksi DEX.", style = MaterialTheme.typography.bodySmall) }
                val lines = if (showPseudo) method.pseudo else method.instructions
                items(lines.withIndex().toList(), key = { "${showPseudo}:${it.index}" }) { line ->
                    if (showPseudo) SelectionContainer { Text(line.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    else InstructionLine(line.value)
                }
                if (method.truncated) item { Text("Tampilan/pemindaian instruksi dibatasi. ${method.instructionCount} instruksi terbaca; daftar bukan keseluruhan method.") }
            }
            1 -> {
                val f = flow
                if (f == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else item(key = "flow") { FlowDiagram(f) }
            }
            2 -> {
                item { Text("${outgoing.size} pemanggilan langsung ditemukan. Ketuk tujuan yang memiliki definisi dalam indeks. Target library/platform dapat berada di luar APK.", style = MaterialTheme.typography.bodySmall) }
                items(outgoing.take(250).withIndex().toList(), key = { it.index }) { indexed ->
                    val call = indexed.value
                    val targets = byDescriptor[call.target].orEmpty()
                    val target = targets.singleOrNull()
                    CodeCard(call.target, "Offset: 0x${call.offset.toString(16)}\n" + when {
                        targets.size > 1 -> "Beberapa definisi ditemukan; tujuan tidak dipilih otomatis."
                        target == null -> "Definisi tidak ditemukan dalam cakupan indeks."
                        else -> "Buka definisi method"
                    }, onClick = target?.let { { open(it.id) } })
                }
            }
            3 -> {
                item { Text("${incoming.size} call-site mengacu pada signature ini. Ini referensi statis, bukan bukti method dieksekusi. Reflection, pemuatan dinamis, invoke-custom, dan resolusi dispatch saat runtime tidak dipetakan.", style = MaterialTheme.typography.bodySmall) }
                if (incoming.isEmpty()) item { Text("Tidak ada pemanggilan langsung dalam instruksi yang berhasil diindeks. Method masih bisa dipanggil framework atau secara dinamis.") }
                items(incoming.take(250).withIndex().toList(), key = { it.index }) { indexed ->
                    val call = indexed.value
                    val caller = byId[call.callerId]
                    CodeCard(caller?.descriptor ?: call.callerId, "Offset: 0x${call.offset.toString(16)}\n${caller?.archive.orEmpty()}/${caller?.dex.orEmpty()}", onClick = caller?.let { { open(it.id) } })
                }
            }
            4 -> if (paths != null) {
                item {
                    Text("Rantai pemanggilan langsung dari komponen manifest (titik masuk yang dijalankan sistem) sampai method ini, terpendek lebih dulu. " +
                        "Pemanggilan lewat superclass/interface ikut dihitung, kecuali interface yang dipakai sangat banyak class (mis. lambda Kotlin); callback framework dan reflection tidak terlihat.", style = MaterialTheme.typography.bodySmall)
                }
                val chains = paths.paths.ifEmpty { paths.partial }
                if (paths.paths.isEmpty()) item {
                    CodeCard("Tidak sampai ke komponen", if (paths.partial.isEmpty()) "Tidak ada pemanggil langsung dalam indeks. Method ini mungkin dipanggil framework (mis. onClick, onReceive), lambda, atau reflection."
                        else "Rantai di bawah berhenti di method yang tidak dipanggil langsung oleh kode terindeks; biasanya callback framework, listener, atau lambda.")
                }
                items(chains.withIndex().toList(), key = { "path${it.index}" }) { (i, chain) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Jalur ${i + 1} · ${chain.size} langkah", style = MaterialTheme.typography.titleSmall)
                            chain.forEachIndexed { step, id ->
                                val target = byId[id]
                                val entry = target?.let { entryPoints[it.owner] }
                                val label = (if (step == 0) "" else "↓ ") + prettyMethod(id) + (entry?.let { " · $it" } ?: "")
                                if (target != null && id != method.id) TextButton(onClick = { open(id) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, style = MaterialTheme.typography.bodySmall) }
                                else Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
                if (paths.truncated) item { Text("Pencarian dibatasi; mungkin ada jalur lain.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if ((tab == 2 && outgoing.size > 250) || (tab == 3 && incoming.size > 250)) item { Text("Daftar menampilkan 250 call-site pertama.") }
    }
}

/** One line of the DEX listing; tapping it shows what the instruction does. */
@Composable
private fun InstructionLine(line: String) {
    var open by rememberSaveable(line) { mutableStateOf(false) }
    val help = remember(line) { explainInstruction(line) }
    Column(Modifier.fillMaxWidth().clickable(enabled = help != null) { open = !open }) {
        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (open && help != null) Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${help.opcode}: ${help.title}", style = MaterialTheme.typography.labelLarge)
                Text(help.explanation, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CodeCard(title: String, body: String, onClick: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer { Text(body, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
