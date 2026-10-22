package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class AuditExportTest {
    private val apps = listOf(
        AppAudit("id.calm", "Tenang", false, "1", 35, false, false, false, "com.android.vending", emptyList(), 1, emptyList()),
        AppAudit("id.evil", "=HYPERLINK(\"http://x\") <script>", false, "2.0", 26, true, true, false, null,
            listOf("android.permission.CAMERA"), 2, listOf("Google AdMob" to "Iklan"), note = "a,b\nc"))

    @Test fun csvQuotesEveryCellAndDefusesFormulas() {
        val csv = apps.toAuditCsv()
        assertTrue(csv.startsWith("﻿\"Aplikasi\",\"Package\""))
        val rows = csv.removePrefix("﻿").split("\r\n").filter { it.isNotEmpty() }
        // Header, then the app with more points first; the embedded newline stays inside its quoted cell.
        assertEquals(3, rows.size)
        assertTrue(rows[1].startsWith("\"'=HYPERLINK(\"\"http://x\"\") <script>\",\"id.evil\",\"2.0\",\"tidak\",\"26\","))
        assertTrue("\"Kamera\",\"Google AdMob (Iklan)\",\"android.permission.CAMERA\"" in rows[1])
        assertTrue(rows[1].endsWith("\"a,b\nc\""))
        assertTrue(rows[2].startsWith("\"Tenang\",\"id.calm\""))
    }

    @Test fun htmlEscapesAppTextAndShowsTheDiff() {
        val diff = AuditDiff(0, listOf(AuditChange("id.evil", "<b>x</b>", CHANGE_NEW, listOf("Pelacak: <i>"), attention = true)))
        val html = apps.toAuditHtml(86_400_000L, diff)
        assertFalse("<script>" in html)
        assertTrue("=HYPERLINK(&quot;http://x&quot;) &lt;script&gt;" in html)
        assertTrue("<strong>⚠ &lt;b&gt;x&lt;/b&gt; · baru dipasang</strong>" in html)
        assertTrue("Pelacak: &lt;i&gt;" in html)
        assertTrue("default-src 'none'" in html)
        assertTrue(html.indexOf("id.evil</code><br>2.0") < html.indexOf("id.calm"))
        assertTrue("<b>1</b><span>Debuggable</span>" in html)
    }
}
