package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class ScreensTest {
    private fun t(name: String) = "L" + name.replace('.', '/') + ";"
    private val launcher = IntentFilterInfo(listOf("android.intent.action.MAIN"), listOf("android.intent.category.LAUNCHER"))
    private val components = listOf(
        AppComponent("id.toko.Main", ComponentKind.ACTIVITY, true, true, null, intentFilters = listOf(launcher)),
        AppComponent("id.toko.Cart", ComponentKind.ACTIVITY, false, true, null),
        AppComponent("id.toko.Pay", ComponentKind.ACTIVITY, false, true, null),
        AppComponent("id.toko.Sync", ComponentKind.SERVICE, false, true, null),
        AppComponent("id.toko.Orphan", ComponentKind.ACTIVITY, false, true, null),
        AppComponent("androidx.work.SystemJobService", ComponentKind.SERVICE, false, true, null))

    @Test fun collectorNeedsAConstClassAndAnIntent() {
        val c = ScreenCollector(components.map { it.name })
        assertTrue(c.wants(t("id.toko.Cart")))
        assertFalse(c.wants("Ljava/lang/String;"))
        assertTrue(c.buildsIntent("Landroid/content/Intent;", "<init>"))
        assertTrue(c.buildsIntent("Landroid/content/ComponentName;", "<init>"))
        assertFalse(c.buildsIntent("Landroid/content/Intent;", "putExtra"))
        c.method("m1", t("id.toko.Main"), listOf(t("id.toko.Cart")), buildsIntent = true)
        // A class literal without an Intent (e.g. a logger tag) is not a link; nor is a class naming itself.
        c.method("m2", t("id.toko.Main"), listOf(t("id.toko.Pay")), buildsIntent = false)
        c.method("m3", t("id.toko.Pay"), listOf(t("id.toko.Pay")), buildsIntent = true)
        c.method("m1", t("id.toko.Main"), listOf(t("id.toko.Cart")), buildsIntent = true)
        assertEquals(listOf(ScreenLink(t("id.toko.Main"), t("id.toko.Cart"), "m1")), c.result())
    }

    @Test fun mapLayersComponentsFromTheLauncher() {
        val report = AnatomyReport("id.toko", "Toko", "1", 24, 35, "-", components, emptyList(), screenLinks = listOf(
            ScreenLink(t("id.toko.Main"), t("id.toko.Cart"), "a"),
            ScreenLink(t("id.toko.Main\$onCreate\$1"), t("id.toko.Cart"), "b"),
            ScreenLink(t("id.toko.Cart"), t("id.toko.Pay"), "c"),
            ScreenLink(t("id.toko.Pay"), t("id.toko.Main"), "d"),
            ScreenLink(t("id.toko.notify.Notifier"), t("id.toko.Sync"), "e"),
            ScreenLink(t("id.toko.Sync\$Companion"), t("id.toko.Sync"), "f"),
            ScreenLink(t("androidx.work.Scheduler"), t("androidx.work.SystemJobService"), "g")))
        val map = report.screenMap()
        assertEquals(1, map.hiddenLibraryEdges)
        // The lambda class counts as Main, so Main → Cart has two methods; Sync's companion starting Sync is no link.
        assertEquals(listOf("a", "b"), map.edges.single { it.from == t("id.toko.Main") }.via)
        assertEquals(4, map.edges.size)
        val level = map.nodes.associate { it.name to it.level }
        assertEquals(mapOf("Main" to 0, "Notifier" to 0, "Cart" to 1, "Sync" to 1, "Pay" to 2), level)
        assertTrue(map.nodes.first().launcher)
        assertNull(map.nodes.single { it.name == "Notifier" }.kind)
        assertEquals(ComponentKind.SERVICE, map.nodes.single { it.name == "Sync" }.kind)
        // Orphan is in no link and is not a launcher, so it is not drawn.
        assertTrue(map.nodes.none { it.name == "Orphan" })
        assertEquals(5, report.screenMap(includeLibraries = true).edges.size)
    }

    @Test fun aCycleWithoutRootStillGetsARow() {
        val report = AnatomyReport("id.toko", "Toko", "1", 24, 35, "-", components.drop(1), emptyList(), screenLinks = listOf(
            ScreenLink(t("id.toko.Cart"), t("id.toko.Pay"), "c"), ScreenLink(t("id.toko.Pay"), t("id.toko.Cart"), "d")))
        assertEquals(listOf(0, 0), report.screenMap().nodes.map { it.level })
    }
}
