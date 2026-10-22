package com.apkanatomy.domain

/** A protection or packing layer found in the APK, with the files or packages that show it. */
data class Protection(val name: String, val kind: String, val explanation: String, val evidence: List<String>)

private const val PACKER = "Packer / pelindung"
private const val HIDDEN = "Kode tersembunyi"
private const val OBFUSCATION = "Penyamaran"
private const val RUNTIME = "Pemeriksaan saat berjalan"

private const val PACKED = "Kode asli biasanya terenkripsi dan baru dibuka di memori saat aplikasi berjalan, jadi tab Kode, Perilaku, dan Jejak hanya melihat \"cangkang\" luarnya."

/** [files] match native library or asset file names (last path segment, lower case); [packages] match DEX packages. */
private class PackerRule(val name: String, val explanation: String, val files: List<Regex> = emptyList(), val packages: List<String> = emptyList())

private val PACKERS = listOf(
    PackerRule("Qihoo 360 Jiagu", "Packer dari Qihoo 360 yang umum di aplikasi Tiongkok. $PACKED",
        files = listOf(Regex("libjiagu.*\\.so")), packages = listOf("com.stub")),
    PackerRule("Bangcle / SecNeo", "Packer komersial Bangcle (SecNeo). $PACKED",
        files = listOf(Regex("libsecexe.*\\.so"), Regex("libsecmain.*\\.so"), Regex("libsecshell.*\\.so"), Regex("secdata0\\.jar")), packages = listOf("com.secneo.apkwrapper")),
    PackerRule("Tencent Legu", "Packer dari Tencent (Legu). $PACKED",
        files = listOf(Regex("libshell[ax]?-.*\\.so"), Regex("libshella.*\\.so"), Regex("libshellx.*\\.so")), packages = listOf("com.tencent.stubshell")),
    PackerRule("Ijiami", "Packer komersial Ijiami. $PACKED",
        files = listOf(Regex("libexecmain\\.so"), Regex("ijiami\\.(dat|ajm)")), packages = listOf("com.shell")),
    PackerRule("Baidu Protect", "Packer dari Baidu. $PACKED", files = listOf(Regex("libbaiduprotect.*\\.so")), packages = listOf("com.baidu.protect")),
    PackerRule("Alibaba Security Guard", "Komponen keamanan Alibaba (anti-tamper, penyimpanan kunci terenkripsi), sering bersama aplikasi Alibaba/Taobao.",
        files = listOf(Regex("libsgmain.*\\.so"), Regex("libsgsecuritybody.*\\.so"), Regex("libmobisec.*\\.so")), packages = listOf("com.alibaba.wireless.security")),
    PackerRule("DexProtector", "Pelindung komersial dari Licel: mengenkripsi kode, string, dan resource, serta memeriksa integritas. $PACKED",
        files = listOf(Regex("libdexprotector.*\\.so"), Regex(".*\\.dex\\.dat"), Regex("dp\\..*\\.so\\.dat"))),
    PackerRule("AppSealing", "Pelindung aplikasi komersial dari INKA (anti-tamper, anti-debug, anti-cheat untuk game).",
        files = listOf(Regex("libcovault.*\\.so")), packages = listOf("com.inka.appsealing")),
    PackerRule("LIAPP", "Pelindung aplikasi komersial dari Lockin Company (anti-tamper, anti-cheat).", files = listOf(Regex("libliapp.*\\.so"))),
)

/** Packers, hidden code, name obfuscation and runtime checks, with the evidence for each. */
fun AnatomyReport.protections(): List<Protection> = buildList {
    val files = structure.entries.map { it.path }
    val names = files.map { it.substringAfterLast('/').lowercase() }
    for (rule in PACKERS) {
        val fileHits = files.filterIndexed { i, _ -> rule.files.any { it.matches(names[i]) } }
        val packageHits = code.packages.filter { p -> rule.packages.any { p == it || p.startsWith("$it.") } }
        if (fileHits.isNotEmpty() || packageHits.isNotEmpty()) add(Protection(rule.name, PACKER, rule.explanation, (fileHits + packageHits.map { "package $it" }).take(5)))
    }

    val hidden = files.filter { p ->
        (p.startsWith("assets/") || p.startsWith("res/raw/")) && listOf(".dex", ".jar", ".odex", ".apk", ".zip.dex").any { p.lowercase().endsWith(it) }
    }
    if (hidden.isNotEmpty()) add(Protection("Kode tambahan di assets", HIDDEN,
        "File DEX/JAR/APK di assets tidak dijalankan Android secara otomatis; aplikasi memuatnya sendiri (plugin, modul terpisah, atau payload packer). Isinya tidak termasuk dalam analisis kode.", hidden.take(5)))

    val dexCount = structure.dex.size
    if (dexCount > 0 && code.definedClasses in 1..40 && structure.libraries.isNotEmpty())
        add(Protection("DEX utama sangat kecil", HIDDEN,
            "Hanya ${code.definedClasses} class di DEX, tetapi ada library native. Pola ini khas packer: DEX hanya berisi pemuat, dan kode sebenarnya dibuka oleh library native saat berjalan.",
            structure.dex.map { "${it.archive}/${it.path}" }.take(3)))

    obfuscationHint(code)?.let { hint ->
        add(Protection("Nama class disamarkan (R8/ProGuard)", OBFUSCATION,
            "$hint Penyamaran mempersulit membaca kode tetapi tidak mengenkripsinya; struktur, string, dan pemanggilan API tetap terlihat.",
            listOf("${code.shortNamedClasses} dari ${code.definedClasses} class")))
    }

    val checks = behaviors.findings.filter { it.id in setOf("root-check", "debugger-check", "integrity", "dex-memory") }
    if (checks.isNotEmpty()) add(Protection("Pemeriksaan lingkungan & integritas", RUNTIME,
        "Kode memeriksa perangkat atau dirinya saat berjalan: ${checks.joinToString { it.title.lowercase() }}. Umum di aplikasi bank, pembayaran, dan game untuk mempersulit modifikasi dan kecurangan.",
        checks.flatMap { it.apis }.distinct().take(5)))
}

/** A packer hides the real code from every code-based tab, so the DNA tab should say so. */
fun List<Protection>.securityNotes(): List<SecurityNote> = filter { it.kind == PACKER || it.name == "DEX utama sangat kecil" }.map {
    SecurityNote(NoteLevel.INFO, "Dilindungi: ${it.name}", it.explanation + " Bukti: " + it.evidence.joinToString() + ".")
}
