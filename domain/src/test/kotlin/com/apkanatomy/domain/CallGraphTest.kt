package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class CallGraphTest {
    private fun m(owner: String, name: String) = CodeMethod("$owner->$name()V", owner, name, "base.apk", "classes.dex", "public", true, emptyList(), 0, false)
    private val main = m("Lapp/Main;", "onCreate")
    private val repo = m("Lapp/Repo;", "load")
    private val api = m("Lapp/Api;", "send")
    private val task = m("Lapp/Task;", "run")
    private val job = m("Lapp/Job;", "start")
    private val orphan = m("Lapp/Orphan;", "go")
    private fun call(from: CodeMethod, to: String) = MethodCall(from.id, to, 0)
    private val index = CodeIndex(
        classes = listOf(CodeClass("Lapp/Task;", "Lapp/Base;", listOf("Ljava/lang/Runnable;"), "base.apk", "classes.dex")),
        methods = listOf(main, repo, api, task, job, orphan),
        calls = listOf(call(main, repo.descriptor), call(repo, api.descriptor),
            // Job runs Task through the Runnable interface; Orphan calls the API but nobody calls Orphan.
            call(job, "Ljava/lang/Runnable;->run()V"), call(task, api.descriptor), call(orphan, api.descriptor)))
    private val graph = CallGraph(index)
    private val entries = listOf(AppComponent("app.Main", ComponentKind.ACTIVITY, true, true, null)).entryTypes().keys

    @Test fun findsTheShortestChainFromAComponent() {
        val result = graph.pathsTo(api.id, entries)
        assertEquals(listOf(listOf(main.id, repo.id, api.id)), result.paths)
        assertTrue(result.partial.isEmpty())
        assertFalse(result.truncated)
    }

    @Test fun followsCallsMadeThroughASupertype() {
        assertEquals(listOf(job.id), graph.callersOf(task.id))
    }

    @Test fun ignoresWidelyImplementedInterfaces() {
        assertTrue(CallGraph(index, maxImplementers = 0).callersOf(task.id).isEmpty())
    }

    @Test fun reportsPartialChainsWhenNoComponentIsReached() {
        val result = graph.pathsTo(api.id, emptySet())
        assertTrue(result.paths.isEmpty())
        assertEquals(setOf(listOf(main.id, repo.id, api.id), listOf(job.id, task.id, api.id), listOf(orphan.id, api.id)), result.partial.toSet())
    }

    @Test fun inheritedMethodsOfAComponentAreEntryPoints() {
        val components = listOf(AppComponent("app.Task", ComponentKind.SERVICE, false, true, null)).entryTypes()
        val entries = CallGraph(index.copy(classes = index.classes + CodeClass("Lapp/Base;", "Ljava/lang/Object;", emptyList(), "base.apk", "classes.dex")))
            .entryPoints(components)
        assertEquals(mapOf("Lapp/Task;" to "service", "Lapp/Base;" to "service lewat Task"), entries)
    }

    @Test fun aComponentMethodIsItsOwnEntryAndLimitsAreReported() {
        assertEquals(listOf(listOf(main.id)), graph.pathsTo(main.id, entries).paths)
        assertTrue(graph.pathsTo(api.id, emptySet(), maxVisited = 2).truncated)
    }
}
