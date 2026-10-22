package com.apkanatomy.domain

/** Who wrote a block of code: the app itself, the Android/Kotlin platform libraries, or a third party. */
enum class CodeOwner(val label: String) {
    APP("Kode aplikasi"), PLATFORM("AndroidX, Kotlin & Google"), LIBRARY("Library pihak ketiga"),
    /** Short renamed packages (a, b, …) left by R8: usually app code and libraries moved together, so their owner is unknown. */
    OBFUSCATED("Kode tersamar"),
}

/** A group of packages (e.g. "okhttp3", "com.google.firebase") with its class count. */
data class CodeBlock(val name: String, val classes: Int, val owner: CodeOwner, val sdk: String? = null)

private val PLATFORM_ROOTS = listOf("androidx", "android", "kotlin", "kotlinx", "java", "javax", "com.google.android.material", "com.google.android.gms",
    "com.google.firebase", "com.google.common", "com.google.protobuf", "dagger", "org.jetbrains")

/** Groups packages by organisation or product (see `root`), largest first; the rest is merged into "lainnya". */
fun AnatomyReport.codeMap(maxBlocks: Int = 24): List<CodeBlock> {
    val appRoots = (listOf(packageName) + components.map { it.name.substringBeforeLast('.') })
        .map { it.split('.').take(2).joinToString(".") }.filter { '.' in it }.toSet()
    fun root(pkg: String): String {
        val parts = pkg.split('.')
        // Reverse-domain names group by organisation ("com.squareup"), big vendors by product ("com.google.firebase"),
        // and bare names by their first segment ("okhttp3", "kotlin").
        val reverseDomain = parts[0].length == 2 || parts[0] in setOf("com", "org", "net", "io", "edu", "gov", "app", "dev", "info", "androidx", "android")
        val depth = when {
            parts.size >= 3 && parts[0] in setOf("com", "org", "io", "net") && parts[1] in setOf("google", "facebook", "squareup", "jetbrains") -> 3
            reverseDomain -> 2
            else -> 1
        }
        val name = parts.take(depth).joinToString(".")
        return if (name.isEmpty() || (depth == 1 && name.length <= 2)) OBFUSCATED_GROUP else name
    }
    val sdkByPackage = sdks.flatMap { sdk -> sdk.evidence.map { it to sdk.name } }
    val grouped = code.packageClasses.entries.groupBy({ root(it.key) }, { it.value }).mapValues { it.value.sum() }
    val blocks = grouped.map { (name, count) ->
        val sdk = sdkByPackage.firstOrNull { (p, _) -> p == name || p.startsWith("$name.") || name.startsWith("$p.") }?.second
        val owner = when {
            name == OBFUSCATED_GROUP -> CodeOwner.OBFUSCATED
            sdk != null && trackerPurpose(sdk) != null -> CodeOwner.LIBRARY
            appRoots.any { name == it || name.startsWith("$it.") || it.startsWith("$name.") } -> CodeOwner.APP
            PLATFORM_ROOTS.any { name == it || name.startsWith("$it.") || it.startsWith("$name.") } -> CodeOwner.PLATFORM
            else -> CodeOwner.LIBRARY
        }
        CodeBlock(name, count, owner, sdk)
    }.sortedByDescending { it.classes }
    if (blocks.size <= maxBlocks) return blocks
    val rest = blocks.drop(maxBlocks - 1)
    return blocks.take(maxBlocks - 1) + CodeBlock("lainnya (${rest.size} grup)", rest.sumOf { it.classes }, CodeOwner.LIBRARY)
}

private const val OBFUSCATED_GROUP = "kode tersamar (a, b, …)"

/** A rectangle in the unit square. */
data class MapRect(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Squarified treemap (Bruls, Huizing & van Wijk): lays out [values] (sorted descending) in a w×h area so each
 * rectangle's area is proportional to its value and rectangles stay close to square.
 */
fun squarify(values: List<Double>, width: Float = 1f, height: Float = 1f): List<MapRect> {
    val total = values.sum()
    if (values.isEmpty() || total <= 0) return values.map { MapRect(0f, 0f, 0f, 0f) }
    val scale = width * height / total
    val areas = values.map { it * scale }
    val result = ArrayList<MapRect>(values.size)
    var x = 0.0; var y = 0.0; var w = width.toDouble(); var h = height.toDouble()
    var i = 0
    fun worst(row: List<Double>, side: Double): Double {
        val sum = row.sum(); val max = row.max(); val min = row.min()
        return maxOf(side * side * max / (sum * sum), sum * sum / (side * side * min))
    }
    while (i < areas.size) {
        val side = minOf(w, h)
        val row = mutableListOf(areas[i])
        var j = i + 1
        while (j < areas.size && worst(row + areas[j], side) <= worst(row, side)) { row += areas[j]; j++ }
        val sum = row.sum()
        if (w >= h) {
            val rowWidth = sum / h
            var cy = y
            row.forEach { a -> val rh = a / rowWidth; result += MapRect(x.toFloat(), cy.toFloat(), rowWidth.toFloat(), rh.toFloat()); cy += rh }
            x += rowWidth; w -= rowWidth
        } else {
            val rowHeight = sum / w
            var cx = x
            row.forEach { a -> val rw = a / rowHeight; result += MapRect(cx.toFloat(), y.toFloat(), rw.toFloat(), rowHeight.toFloat()); cx += rw }
            y += rowHeight; h -= rowHeight
        }
        i = j
    }
    return result
}
