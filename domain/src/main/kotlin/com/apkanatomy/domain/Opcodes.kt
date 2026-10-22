package com.apkanatomy.domain

/** What a DEX instruction does, for learners: [title] in a few words, then the [explanation]. */
data class OpcodeHelp(val opcode: String, val title: String, val explanation: String)

private class Family(val matches: (String) -> Boolean, val title: String, val explanation: (String) -> String)

private fun prefix(vararg p: String): (String) -> Boolean = { op -> p.any { op == it || op.startsWith("$it-") || op.startsWith("$it/") } }

private const val RANGE = " Akhiran /range berarti argumennya ada di register berurutan (dipakai bila lebih dari lima)."

private val TYPES = mapOf("wide" to "long/double (2 register)", "object" to "referensi objek", "boolean" to "boolean", "byte" to "byte",
    "char" to "char", "short" to "short")

private fun typeNote(op: String): String = TYPES.entries.firstOrNull { op.contains("-${it.key}") }?.let { " Varian ini untuk ${it.value}." } ?: ""

private val ARITH = mapOf("add" to "penjumlahan (+)", "sub" to "pengurangan (-)", "mul" to "perkalian (*)", "div" to "pembagian (/)", "rem" to "sisa bagi (%)",
    "and" to "AND bit (&)", "or" to "OR bit (|)", "xor" to "XOR bit (^)", "shl" to "geser kiri (<<)", "shr" to "geser kanan bertanda (>>)", "ushr" to "geser kanan tanpa tanda (>>>)")

private val FAMILIES = listOf(
    Family({ it == "nop" }, "Tidak melakukan apa-apa") { "Instruksi kosong. Kadang dipakai sebagai pengisi agar data berikutnya (tabel switch, array) rata ke batas 4 byte." },
    Family(prefix("move-result"), "Mengambil hasil pemanggilan") { op -> "Menyimpan nilai kembalian invoke-* tepat sebelumnya ke register tujuan.${typeNote(op)}" },
    Family({ it == "move-exception" }, "Mengambil exception yang ditangkap") { "Instruksi pertama blok catch: menyimpan objek exception yang sedang ditangani ke register." },
    Family(prefix("move"), "Menyalin register") { op -> "Menyalin nilai dari register kedua ke register pertama. Akhiran /from16 atau /16 hanya memperluas nomor register yang bisa dipakai.${typeNote(op)}" },
    Family(prefix("return-void"), "Selesai, tanpa nilai kembalian") { "Mengakhiri method void dan kembali ke pemanggil." },
    Family(prefix("return"), "Mengembalikan nilai") { op -> "Mengakhiri method dan mengembalikan isi register ke pemanggil.${typeNote(op)}" },
    Family(prefix("const-string"), "Memuat string") { "Memuat string dari string pool DEX ke register. String inilah yang dicari tab Jejak dan pencarian string." },
    Family({ it == "const-class" }, "Memuat objek Class") { "Sama dengan X.class di Java/Kotlin. Sering dipakai untuk Intent eksplisit (membuka Activity atau Service tertentu) dan reflection." },
    Family(prefix("const-method-handle", "const-method-type"), "Memuat MethodHandle") { "Memuat referensi method atau tipe method untuk pemanggilan dinamis (API 28+)." },
    Family(prefix("const-wide"), "Memuat angka 64-bit") { "Memuat konstanta long/double ke dua register berurutan. /16, /32, dan /high16 hanya menentukan berapa bit yang disimpan di instruksi." },
    Family(prefix("const"), "Memuat angka konstanta") { op ->
        "Memuat konstanta ke register. " + when {
            op.endsWith("/4") -> "/4 menyimpan nilainya dalam 4 bit (-8 sampai 7), cukup untuk 0, 1, true, false, atau null."
            op.endsWith("/high16") -> "/high16 mengisi 16 bit atas; dipakai untuk flag seperti 0x02000000 atau angka float."
            op.endsWith("/16") -> "/16 menyimpan nilai 16 bit (-32768 sampai 32767)."
            else -> "Nilai 32 bit penuh, sering ID resource seperti 0x7f0a0001 (R.id, R.string, dan seterusnya)."
        }
    },
    Family(prefix("monitor-enter"), "Masuk blok synchronized") { "Mengunci objek agar hanya satu thread yang menjalankan bagian ini; pasangannya monitor-exit." },
    Family(prefix("monitor-exit"), "Keluar blok synchronized") { "Melepas kunci objek yang diambil monitor-enter." },
    Family({ it == "check-cast" }, "Cast tipe") { "Memastikan objek bertipe tertentu, seperti (Tipe) obj di Java. Gagal → ClassCastException." },
    Family({ it == "instance-of" }, "Cek instanceof") { "Menulis 1 ke register bila objek bertipe tertentu, 0 bila tidak." },
    Family({ it == "array-length" }, "Panjang array") { "Menyimpan jumlah elemen array ke register (arr.length)." },
    Family({ it == "new-instance" }, "Membuat objek") { "Mengalokasikan objek baru tanpa menjalankan konstruktornya. Konstruktor dipanggil sesudahnya dengan invoke-direct ke <init>." },
    Family({ it == "new-array" }, "Membuat array") { "Membuat array baru dengan panjang dari register kedua." },
    Family(prefix("filled-new-array"), "Membuat array berisi") { op -> "Membuat array dan langsung mengisinya dari beberapa register; hasilnya diambil dengan move-result-object.${if (op.endsWith("/range")) RANGE else ""}" },
    Family({ it == "fill-array-data" }, "Mengisi array dari tabel") { "Mengisi array dengan data konstan yang disimpan di akhir method (array-payload), misalnya byte[] kunci atau tabel angka." },
    Family({ it == "throw" }, "Melempar exception") { "Melempar objek exception di register; eksekusi pindah ke blok catch yang cocok atau keluar dari method." },
    Family(prefix("goto"), "Lompat") { "Lompat tanpa syarat ke offset lain. Lompat mundur menandakan perulangan (loop). /16 dan /32 untuk jarak yang lebih jauh." },
    Family({ it == "packed-switch" }, "Switch dengan kunci berurutan") { "Switch yang kuncinya berurutan (mis. 0, 1, 2) memakai tabel lompat langsung. Tidak ada yang cocok → lanjut ke instruksi berikutnya (default)." },
    Family({ it == "sparse-switch" }, "Switch dengan kunci acak") { "Switch yang kuncinya berjauhan (mis. 7, 100, 4096) memakai tabel yang dicari. Switch atas String biasanya memakai hashCode() di sini." },
    Family(prefix("cmpl", "cmpg", "cmp"), "Membandingkan dua angka") { op -> "Menulis -1, 0, atau 1 ke register (lebih kecil, sama, lebih besar); biasanya diikuti if-*. cmpl dan cmpg hanya berbeda saat salah satu nilainya NaN.${if ("long" in op) " Varian ini untuk long." else ""}" },
    Family({ Regex("if-(eq|ne|lt|ge|gt|le)z").matches(it) }, "Lompat bila dibanding nol") { op ->
        val cmp = mapOf("eq" to "sama dengan", "ne" to "tidak sama dengan", "lt" to "kurang dari", "ge" to "lebih atau sama dengan", "gt" to "lebih dari", "le" to "kurang atau sama dengan").getValue(op.removePrefix("if-").removeSuffix("z"))
        "Lompat bila register $cmp 0. Untuk objek, nol berarti null (if-eqz = if (x == null)); untuk boolean, nol berarti false."
    },
    Family({ Regex("if-(eq|ne|lt|ge|gt|le)").matches(it) }, "Lompat bila dua nilai dibandingkan") { op ->
        "Lompat bila register pertama ${mapOf("eq" to "==", "ne" to "!=", "lt" to "<", "ge" to ">=", "gt" to ">", "le" to "<=").getValue(op.removePrefix("if-"))} register kedua; bila tidak, lanjut ke instruksi berikutnya."
    },
    Family(prefix("aget"), "Membaca elemen array") { op -> "Membaca arr[i] ke register.${typeNote(op)}" },
    Family(prefix("aput"), "Menulis elemen array") { op -> "Menulis isi register ke arr[i].${typeNote(op)}" },
    Family(prefix("iget"), "Membaca field objek") { op -> "Membaca field milik sebuah objek (obj.field) ke register.${typeNote(op)}" },
    Family(prefix("iput"), "Menulis field objek") { op -> "Menulis register ke field milik sebuah objek (obj.field = x).${typeNote(op)}" },
    Family(prefix("sget"), "Membaca field static") { op -> "Membaca field static (Kelas.field), misalnya konstanta atau singleton.${typeNote(op)}" },
    Family(prefix("sput"), "Menulis field static") { op -> "Menulis ke field static (Kelas.field = x).${typeNote(op)}" },
    Family(prefix("invoke-virtual"), "Memanggil method objek") { op -> "Memanggil method instance biasa. Method yang benar-benar jalan ditentukan saat runtime dari tipe objeknya (bisa versi subclass). Register pertama adalah objeknya (this).${if (op.endsWith("/range")) RANGE else ""}" },
    Family(prefix("invoke-super"), "Memanggil versi superclass") { op -> "Seperti super.method() di Java: memanggil implementasi milik superclass, misalnya super.onCreate().${if (op.endsWith("/range")) RANGE else ""}" },
    Family(prefix("invoke-direct"), "Memanggil konstruktor atau method private") { op -> "Memanggil method tanpa dispatch virtual: konstruktor (<init>) atau method private.${if (op.endsWith("/range")) RANGE else ""}" },
    Family(prefix("invoke-static"), "Memanggil method static") { op -> "Memanggil method static; tidak ada objek this. Kotlin juga memakainya untuk fungsi top-level dan extension.${if (op.endsWith("/range")) RANGE else ""}" },
    Family(prefix("invoke-interface"), "Memanggil method interface") { op -> "Memanggil method lewat tipe interface (mis. List.add, Runnable.run); implementasinya dicari saat runtime.${if (op.endsWith("/range")) RANGE else ""}" },
    Family(prefix("invoke-polymorphic", "invoke-custom"), "Pemanggilan dinamis") { "Pemanggilan lewat MethodHandle atau invokedynamic (API 26+). Tujuannya baru diketahui saat runtime, sehingga Find usage tidak melihatnya." },
    Family({ it.startsWith("neg-") || it.startsWith("not-") }, "Negasi") { op -> if (op.startsWith("neg")) "Membalik tanda angka (-x)." else "Membalik semua bit (~x)." },
    Family({ Regex("(int|long|float|double)-to-(int|long|float|double|byte|char|short)").matches(it) }, "Konversi tipe") { op ->
        "Mengubah ${op.substringBefore("-to-")} menjadi ${op.substringAfter("-to-")}, seperti cast (${op.substringAfter("-to-")}) x."
    },
    Family({ it.startsWith("rsub-int") }, "Konstanta dikurangi register") { "Menghitung konstanta - register (kebalikan urutan sub)." },
    Family({ op -> ARITH.keys.any { op.startsWith("$it-") } }, "Operasi aritmetika") { op ->
        val name = ARITH.getValue(op.substringBefore('-'))
        "Operasi $name. " + when {
            op.endsWith("/2addr") -> "/2addr: hasilnya ditulis kembali ke register pertama (a = a op b)."
            "/lit" in op -> "/lit8 atau /lit16: operand kedua adalah konstanta di instruksi itu sendiri."
            else -> "Hasil ditulis ke register pertama dari dua register berikutnya."
        } + if (op.startsWith("div") || op.startsWith("rem")) " Pembagian bilangan bulat dengan nol melempar ArithmeticException." else ""
    },
)

/**
 * Explains the instruction on a line of the DEX listing ("0x1a  invoke-virtual v0, ..."). The opcode is the
 * first word after the offset; null when it is not one this guide knows.
 */
fun explainInstruction(line: String): OpcodeHelp? {
    val op = line.trim().split(Regex("\\s+")).let { words -> if (words.firstOrNull()?.startsWith("0x") == true) words.getOrNull(1) else words.firstOrNull() } ?: return null
    val family = FAMILIES.firstOrNull { it.matches(op) } ?: return null
    return OpcodeHelp(op, family.title, family.explanation(op))
}

/** Register names in the listing: worth one line of explanation the first time a learner opens it. */
const val REGISTER_NOTE = "v0, v1, … adalah register lokal method; p0, p1, … adalah parameter (di method non-static, p0 adalah this). DEX bekerja dengan register, bukan stack seperti bytecode Java."

/** The file name a class was compiled from, when the DEX kept a real one (R8 may write "SourceFile" or an r8-map-id). */
fun CodeClass.realSourceFile(): String? = sourceFile?.takeIf { it.isNotBlank() && it != "SourceFile" && !it.startsWith("r8-map-id") }

/** For a class whose name looks obfuscated (one or two letters), the original file name kept in its SourceFile attribute. */
fun CodeClass.sourceHint(): String? {
    val simple = type.removePrefix("L").removeSuffix(";").substringAfterLast('/').substringBefore('$')
    val file = realSourceFile() ?: return null
    return file.takeIf { simple.length <= 2 && file.substringBeforeLast('.') != simple }
}
