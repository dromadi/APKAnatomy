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
internal fun CodeExplorer(index: CodeIndex) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedClassId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMethodId by rememberSaveable { mutableStateOf<String?>(null) }
    var methodHistory by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    fun backMethod() {
        selectedMethodId = methodHistory.lastOrNull()
        methodHistory = methodHistory.dropLast(1)
    }
    var searchMethods by rememberSaveable { mutableStateOf(false) }
    val methodById = remember(index) { index.methods.associateBy { it.id } }
    val methodsByDescriptor = remember(index) { index.methods.groupBy { it.descriptor } }
    val selectedClass = remember(index, selectedClassId) { index.classes.find { it.id == selectedClassId } }
    val selectedMethod = methodById[selectedMethodId]
    BackHandler(selectedClass != null || selectedMethod != null) {
        if (selectedMethod != null) backMethod() else selectedClassId = null
    }
    if (selectedMethod != null) {
        MethodDetail(index, selectedMethod, methodById, methodsByDescriptor,
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
            item { CodeCard(selectedClass.displayName, "${selectedClass.archive}/${selectedClass.dex}\nSuperclass: ${selectedClass.superType ?: "—"}\nInterface: ${selectedClass.interfaces.joinToString().ifEmpty { "—" }}") }
            item { Text("${methods.size} method terdefinisi dalam cakupan indeks. Nama dapat disamarkan oleh obfuscation.") }
            items(methods.take(500), key = { it.id }) { method -> CodeCard(method.descriptor, method.access, onClick = { selectedMethodId = method.id }) }
            if (methods.size > 500) item { Text("Daftar dibatasi 500 method; gunakan pencarian method untuk menemukan yang lain.") }
        }
        return
    }
    OutlinedTextField(query, { query = it }, label = { Text("Cari class / nama / signature method") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row { Checkbox(searchMethods, { searchMethods = it }); Text("Cari method", modifier = Modifier.padding(top = 14.dp)) }
    val classes = remember(index, query) { index.classes.filter { it.displayName.contains(query, true) } }
    val methods = remember(index, query) { index.methods.filter { it.descriptor.contains(query, true) } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { CodeCard("Mulai menelusuri", "Cari nama fitur, class, atau method. Buka method untuk melihat instruksi DEX, pemanggilan keluar, dan Find usage. Pencarian ini mencari nama/signature, belum seluruh isi kode atau string.") }
        item { Text("${index.classes.size} class · ${index.methods.size} method · ${index.calls.size} pemanggilan langsung. Hasil bukan kode Java hasil dekompilasi.", style = MaterialTheme.typography.bodySmall) }
        items(index.warnings) { CodeCard("Cakupan terbatas", it) }
        if (searchMethods) {
            item { Text("${methods.size} hasil method") }
            items(methods.take(250), key = { it.id }) { method -> CodeCard(method.descriptor, "${method.archive}/${method.dex}", onClick = { selectedMethodId = method.id }) }
            if (methods.size > 250) item { Text("Menampilkan 250 hasil; persempit pencarian.") }
        } else {
            item { Text("${classes.size} hasil class") }
            items(classes.take(250), key = { it.id }) { cls -> CodeCard(cls.displayName, "${cls.archive}/${cls.dex}", onClick = { selectedClassId = cls.id }) }
            if (classes.size > 250) item { Text("Menampilkan 250 hasil; persempit pencarian.") }
        }
    }
}

@Composable
private fun MethodDetail(index: CodeIndex, method: CodeMethod,
    byId: Map<String, CodeMethod>, byDescriptor: Map<String, List<CodeMethod>>,
    back: () -> Unit, open: (String) -> Unit) {
    var tab by rememberSaveable(method.id) { mutableIntStateOf(0) }
    TextButton(onClick = back) { Text("Kembali") }
    PrimaryTabRow(selectedTabIndex = tab) {
        listOf("Instruksi", "Memanggil", "Find usage").forEachIndexed { i, label -> Tab(tab == i, { tab = i }, text = { Text(label) }) }
    }
    val outgoing = remember(index, method) { index.callsFrom(method.id) }
    val incoming = remember(index, method) { index.callersOf(method.descriptor) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item { CodeCard(method.descriptor, "${method.access}\n${method.archive}/${method.dex}") }
        if (index.warnings.isNotEmpty()) item { CodeCard("Indeks sebagian", "Ada batas atau kegagalan pemindaian. Hasil Find usage tidak mencakup seluruh aplikasi; lihat peringatan pada pencarian kode.") }
        when (tab) {
            0 -> {
                item { Text("Instruksi ditampilkan dengan offset dalam code unit DEX. Ini ringkasan disassembly, bukan Java atau Smali lengkap yang bisa dikompilasi.", style = MaterialTheme.typography.bodySmall) }
                if (!method.hasCode) item { Text("Tidak ada implementasi DEX untuk method ini; bisa berupa abstract atau native.") }
                items(method.instructions.withIndex().toList(), key = { it.index }) { line -> SelectionContainer { Text(line.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                if (method.truncated) item { Text("Tampilan/pemindaian instruksi dibatasi. ${method.instructionCount} instruksi terbaca; daftar bukan keseluruhan method.") }
            }
            1 -> {
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
            2 -> {
                item { Text("${incoming.size} call-site mengacu pada signature ini. Ini referensi statis, bukan bukti method dieksekusi. Reflection, pemuatan dinamis, invoke-custom, dan resolusi dispatch saat runtime tidak dipetakan.", style = MaterialTheme.typography.bodySmall) }
                if (incoming.isEmpty()) item { Text("Tidak ada pemanggilan langsung dalam instruksi yang berhasil diindeks. Method masih bisa dipanggil framework atau secara dinamis.") }
                items(incoming.take(250).withIndex().toList(), key = { it.index }) { indexed ->
                    val call = indexed.value
                    val caller = byId[call.callerId]
                    CodeCard(caller?.descriptor ?: call.callerId, "Offset: 0x${call.offset.toString(16)}\n${caller?.archive.orEmpty()}/${caller?.dex.orEmpty()}", onClick = caller?.let { { open(it.id) } })
                }
            }
        }
        if ((tab == 1 && outgoing.size > 250) || (tab == 2 && incoming.size > 250)) item { Text("Daftar menampilkan 250 call-site pertama.") }
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
