package com.apkanatomy.domain

data class CodeClass(val type: String, val superType: String?, val interfaces: List<String>,
    val archive: String, val dex: String) {
    val id: String get() = "$archive:$dex:$type"
    val displayName: String get() = type.removePrefix("L").removeSuffix(";").replace('/', '.')
}
data class CodeMethod(val descriptor: String, val owner: String, val name: String,
    val archive: String, val dex: String, val access: String, val hasCode: Boolean,
    val instructions: List<String>, val instructionCount: Int, val truncated: Boolean) {
    val id: String get() = "$archive:$dex:$descriptor"
}
data class MethodCall(val callerId: String, val target: String, val offset: Int)
data class CodeIndex(val classes: List<CodeClass> = emptyList(), val methods: List<CodeMethod> = emptyList(),
    val calls: List<MethodCall> = emptyList(), val warnings: List<String> = emptyList(),
    /** Dotted packages of every class definition in the DEX files read, beyond the indexing limits. */
    val packages: List<String> = emptyList(),
    /** Class definitions seen in those DEX files, and how many have a one- or two-letter simple name. */
    val definedClasses: Int = 0, val shortNamedClasses: Int = 0) {
    fun callersOf(descriptor: String): List<MethodCall> = calls.filter { it.target == descriptor }
    fun callsFrom(methodId: String): List<MethodCall> = calls.filter { it.callerId == methodId }
}
