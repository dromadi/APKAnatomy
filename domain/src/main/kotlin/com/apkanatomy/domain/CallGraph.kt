package com.apkanatomy.domain

/**
 * How a method can be reached, each chain as method IDs from the first caller down to the method.
 * [paths] start at an entry point (a method of a manifest component); [partial] start at callers that
 * nothing in the index calls, and are only filled when no entry point was reached.
 */
data class CallPaths(val paths: List<List<String>>, val partial: List<List<String>>, val truncated: Boolean)

/**
 * Reverse call graph over the direct calls in a [CodeIndex]. A call to `Base;->run()V` also counts for
 * an override in a subclass, because the index records the declared target, not the runtime one. Supertypes
 * with more than [maxImplementers] indexed subtypes (Kotlin's Function1, Runnable in large apps) are not
 * followed: a call through them could reach any of those, which would invent paths.
 */
class CallGraph(private val index: CodeIndex, private val maxImplementers: Int = 20) {
    private val callersByTarget: Map<String, List<String>> = index.calls.groupBy({ it.target }, { it.callerId })
    private val classByType: Map<String, CodeClass> = index.classes.associateBy { it.type }
    private val methodById: Map<String, CodeMethod> = index.methods.associateBy { it.id }
    private val implementers: Map<String, Int> = index.classes.flatMap { listOfNotNull(it.superType) + it.interfaces }.groupingBy { it }.eachCount()

    /** Callers of [methodId], including calls made through a supertype's declaration. */
    fun callersOf(methodId: String): List<String> {
        val method = methodById[methodId] ?: return emptyList()
        val signature = method.descriptor.substringAfter("->")
        return (listOf(method.owner) + ancestors(method.owner).filter { (implementers[it] ?: 0) <= maxImplementers }).flatMap { callersByTarget["$it->$signature"].orEmpty() }.distinct()
    }

    /**
     * Types whose methods the system can run as an entry point, with a label: the component classes themselves,
     * and their indexed superclasses, whose inherited methods (e.g. AppCompatActivity.attachBaseContext) run for the component.
     */
    fun entryPoints(components: Map<String, ComponentKind>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        components.forEach { (type, kind) -> result[type] = kind.name.lowercase() }
        components.forEach { (type, kind) ->
            ancestors(type).filter { it in classByType && it !in result }
                .forEach { result[it] = "${kind.name.lowercase()} lewat ${type.removePrefix("L").removeSuffix(";").substringAfterLast('/')}" }
        }
        return result
    }

    private fun ancestors(type: String): List<String> {
        val result = linkedSetOf<String>()
        val queue = ArrayDeque(listOf(type))
        while (queue.isNotEmpty() && result.size < 64) {
            val cls = classByType[queue.removeFirst()] ?: continue
            (listOfNotNull(cls.superType) + cls.interfaces).forEach { if (result.add(it)) queue.add(it) }
        }
        return result.toList()
    }

    /**
     * Breadth-first search backwards from [methodId] until methods whose owner is in [entryTypes],
     * so the shortest chains come first.
     */
    fun pathsTo(methodId: String, entryTypes: Set<String>, maxPaths: Int = 3, maxVisited: Int = 5_000): CallPaths {
        val parent = HashMap<String, String?>().apply { put(methodId, null) }
        val queue = ArrayDeque(listOf(methodId))
        val paths = mutableListOf<List<String>>()
        val roots = mutableListOf<String>()
        var truncated = false
        fun chainFrom(start: String): List<String> = generateSequence(start) { parent[it] }.toList()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (methodById[current]?.owner in entryTypes) {
                paths += chainFrom(current)
                if (paths.size >= maxPaths) { truncated = queue.isNotEmpty(); break }
                continue
            }
            val callers = callersOf(current)
            if (callers.isEmpty() && current != methodId && roots.size < maxPaths) roots += current
            for (caller in callers) {
                if (caller in parent) continue
                if (parent.size >= maxVisited) { truncated = true; break }
                parent[caller] = current
                queue.add(caller)
            }
        }
        return CallPaths(paths, if (paths.isEmpty()) roots.map(::chainFrom) else emptyList(), truncated)
    }
}

/** Manifest component class names as DEX types, e.g. "com.x.Main" → "Lcom/x/Main;". */
fun List<AppComponent>.entryTypes(): Map<String, ComponentKind> = associate { "L" + it.name.replace('.', '/') + ";" to it.kind }
