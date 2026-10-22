package com.apkanatomy.domain

/** Code in [methodId] (a method of class [from]) builds an Intent or ComponentName for component [to]. Types are DEX descriptors. */
data class ScreenLink(val from: String, val to: String, val methodId: String) : java.io.Serializable

/**
 * Collects component links while the code is scanned: a method that loads `SomeActivity.class` (const-class)
 * of a manifest component and also builds an Intent or ComponentName most likely starts it, as in
 * `startActivity(new Intent(this, SomeActivity.class))` or `startService(Intent(context, Sync::class.java))`.
 */
class ScreenCollector(components: Collection<String>, private val maxLinks: Int = 5_000) {
    private val types = components.mapTo(HashSet()) { "L" + it.replace('.', '/') + ";" }
    private val links = LinkedHashSet<ScreenLink>()

    /** Whether a const-class of [type] is worth recording. */
    fun wants(type: String): Boolean = type in types

    /** Whether a call to [owner].[name] builds an explicit Intent or ComponentName. */
    fun buildsIntent(owner: String, name: String): Boolean =
        (owner == "Landroid/content/Intent;" && (name == "<init>" || name == "setClass" || name == "setComponent")) ||
            (owner == "Landroid/content/ComponentName;" && name == "<init>")

    fun method(methodId: String, owner: String, targets: Collection<String>, buildsIntent: Boolean) {
        if (!buildsIntent || targets.isEmpty()) return
        for (to in targets) {
            if (links.size >= maxLinks) return
            if (to != owner) links += ScreenLink(owner, to, methodId)
        }
    }

    fun result(): List<ScreenLink> = links.toList()
}

/**
 * A box in the component map: a manifest component, or another class that starts one ([kind] null, e.g. a
 * notification helper or a Compose screen file). [level] counts steps from the roots: launcher activities
 * first, then anything nothing else starts.
 */
data class ScreenNode(val type: String, val name: String, val kind: ComponentKind?, val launcher: Boolean, val exported: Boolean, val level: Int)

/** [from] starts [to]; [via] are the methods that do it. */
data class ScreenEdge(val from: String, val to: String, val via: List<String>)

data class ScreenMap(val nodes: List<ScreenNode>, val edges: List<ScreenEdge>, val hiddenLibraryEdges: Int)

/**
 * Which component starts which, from [AnatomyReport.screenLinks]. An inner or lambda class
 * (`Main$onCreate$1`) counts as its outer class. Links where neither side is in the app's own namespace
 * (the first two parts of the package name) are left out unless [includeLibraries]. Navigation inside
 * one activity (fragments, Compose), deep links and reflection are not visible here.
 */
fun AnatomyReport.screenMap(includeLibraries: Boolean = false): ScreenMap {
    val byType = components.associateBy { "L" + it.name.replace('.', '/') + ";" }
    val own = "L" + packageName.split('.').take(2).joinToString("/") + "/"
    fun outer(type: String): String = if (type in byType) type else type.substringBefore('$').let { if (it != type) "$it;" else type }
    val all = screenLinks.map { Triple(outer(it.from), it.to, it.methodId) }.filter { it.first != it.second }
    val kept = all.filter { includeLibraries || it.first.startsWith(own) || it.second.startsWith(own) }
    val edges = kept.groupBy { it.first to it.second }
        .map { (key, list) -> ScreenEdge(key.first, key.second, list.map { it.third }.distinct()) }
        .sortedWith(compareBy({ it.from }, { it.to }))
    val launchers = components.filter { c -> c.kind == ComponentKind.ACTIVITY && c.intentFilters.any { "android.intent.category.LAUNCHER" in it.categories } }
        .map { "L" + it.name.replace('.', '/') + ";" }
    val types = (launchers + edges.flatMap { listOf(it.from, it.to) }).distinct()
    val incoming = edges.mapTo(HashSet()) { it.to }
    val next = edges.groupBy({ it.from }, { it.to })
    val levels = HashMap<String, Int>()
    // Breadth-first from the roots; anything left (a cycle with no root) goes one row below the rest.
    val queue = ArrayDeque<String>()
    (launchers + types.filter { it !in incoming && it !in launchers }.sorted()).forEach { if (levels.putIfAbsent(it, 0) == null) queue += it }
    while (queue.isNotEmpty()) {
        val at = queue.removeFirst()
        next[at].orEmpty().forEach { to -> if (to !in levels) { levels[to] = levels.getValue(at) + 1; queue += to } }
    }
    val rest = (levels.values.maxOrNull() ?: -1) + 1
    val nodes = types.map { type ->
        val c = byType[type]
        val simple = type.removePrefix("L").removeSuffix(";").substringAfterLast('/')
        ScreenNode(type, simple, c?.kind, type in launchers, c?.exported == true, levels[type] ?: rest)
    }.sortedWith(compareBy({ it.level }, { !it.launcher }, { it.name }))
    return ScreenMap(nodes, edges, all.size - kept.size)
}
