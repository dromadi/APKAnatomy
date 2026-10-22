package com.apkanatomy.domain

data class CodeClass(val type: String, val superType: String?, val interfaces: List<String>,
    val archive: String, val dex: String) : java.io.Serializable {
    val id: String get() = "$archive:$dex:$type"
    val displayName: String get() = type.removePrefix("L").removeSuffix(";").replace('/', '.')
}
data class CodeMethod(val descriptor: String, val owner: String, val name: String,
    val archive: String, val dex: String, val access: String, val hasCode: Boolean,
    val instructions: List<String>, val instructionCount: Int, val truncated: Boolean,
    /** Java-like reading of the instructions; empty when not rendered (no code, or the display budget was used up). */
    val pseudo: List<String> = emptyList()) : java.io.Serializable {
    val id: String get() = "$archive:$dex:$descriptor"
}
data class MethodCall(val callerId: String, val target: String, val offset: Int) : java.io.Serializable
/** A string literal loaded by a method (const-string), truncated to 300 characters. */
data class StringUse(val methodId: String, val value: String) : java.io.Serializable
data class CodeIndex(val classes: List<CodeClass> = emptyList(), val methods: List<CodeMethod> = emptyList(),
    val calls: List<MethodCall> = emptyList(), val warnings: List<String> = emptyList(),
    /** Dotted packages of every class definition in the DEX files read, beyond the indexing limits. */
    val packages: List<String> = emptyList(),
    /** Class definitions seen in those DEX files, and how many have a one- or two-letter simple name. */
    val definedClasses: Int = 0, val shortNamedClasses: Int = 0,
    val strings: List<StringUse> = emptyList(),
    /** Class definitions per dotted package, over every DEX read (beyond the index limits). */
    val packageClasses: Map<String, Int> = emptyMap()) : java.io.Serializable {
    fun callersOf(descriptor: String): List<MethodCall> = calls.filter { it.target == descriptor }
    fun callsFrom(methodId: String): List<MethodCall> = calls.filter { it.callerId == methodId }
}

/** "base.apk:classes.dex:Lcom/x/Api;->login(Ljava/lang/String;)V" → "com.x.Api.login(…)". */
fun prettyMethod(methodId: String): String {
    val descriptor = methodId.substringAfter(':').substringAfter(':')
    val owner = descriptor.substringBefore(";->").removePrefix("L").replace('/', '.')
    val name = descriptor.substringAfter(";->").substringBefore('(')
    val hasArgs = descriptor.substringAfter('(').substringBefore(')').isNotEmpty()
    return if (";->" !in descriptor) descriptor else "$owner.$name(${if (hasArgs) "…" else ""})"
}
