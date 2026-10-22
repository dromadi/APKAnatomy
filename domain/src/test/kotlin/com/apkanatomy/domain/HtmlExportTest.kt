package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class HtmlExportTest {
    private val hostile = "<script>alert(1)</script>"
    private val report = AnatomyReport("com.x", "Toko \"$hostile\"", "1.0", 26, 35, "File APK lokal",
        components = listOf(AppComponent("com.x.$hostile|Main", ComponentKind.ACTIVITY, true, true, null)),
        permissions = listOf(AppPermission("android.permission.CAMERA", "Dangerous")),
        iconPng = byteArrayOf(1, 2, 3))

    @Test fun escapesEverythingFromTheApkAndForbidsScripts() {
        val html = report.toHtml(0)
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(html.contains("default-src 'none'"))
        assertTrue(html.contains("data:image/png;base64,AQID"))
        assertTrue(html.startsWith("<!doctype html>"))
    }

    @Test fun rendersSectionsTablesAndLists() {
        val html = report.toHtml(0)
        assertTrue(html.contains("<h2>Kerangka</h2>"))
        // A pipe inside a cell (escaped in Markdown) stays inside that cell.
        assertTrue(html.contains("<td><code>com.x.&lt;script&gt;alert(1)&lt;/script&gt;|Main</code></td>"))
        assertTrue(html.contains("<li><code>android.permission.CAMERA</code> (Dangerous)"))
        assertTrue(html.contains("<span>Izin</span>"))
    }

    @Test fun convertsTheMarkdownSubset() {
        assertEquals("<h2>A &amp; B</h2>\n<p>x <strong>y</strong> <code>z</code><br><em>w</em></p>\n<ul>\n<li>1</li>\n<li>2</li>\n</ul>\n",
            markdownToHtml("## A & B\nx **y** `z`\n_w_\n\n- 1\n- 2"))
    }
}
