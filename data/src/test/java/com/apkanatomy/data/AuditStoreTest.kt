package com.apkanatomy.data

import com.apkanatomy.domain.AppAudit
import com.apkanatomy.domain.AuditSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AuditStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun keepsTheLastAuditAndDropsUnreadableFiles() {
        val dir = File(temp.root, "audit")
        val store = AuditStore(dir)
        assertNull(store.load())
        val apps = listOf(
            AppAudit("id.a", "A", false, "1.0", 34, true, false, true, null, listOf("android.permission.CAMERA"), 2, listOf("Sentry" to "Pelaporan crash"), "catatan"),
            AppAudit("id.b", "B", true, "2", 30, false, true, false, "com.android.vending", emptyList(), 0, emptyList()))
        store.save(AuditSnapshot(42, true, apps))
        assertEquals(AuditSnapshot(42, true, apps), store.load())
        store.save(AuditSnapshot(43, false, apps.take(1)))
        assertEquals(43L, store.load()!!.savedAt)
        assertEquals(1, dir.listFiles()!!.size)
        // A truncated or foreign file is deleted instead of crashing the audit screen.
        dir.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.load())
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
