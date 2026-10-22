package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class PermissionUseTest {
    private val behaviors = BehaviorCollector("com.x").apply {
        method("base.apk:classes.dex:Lcom/x/Map;->here()V", listOf("Landroid/location/LocationManager;" to "getLastKnownLocation"), emptyList(), 3)
        method("base.apk:classes.dex:Llib/Rec;->go()V", listOf("Landroid/media/AudioRecord;" to "startRecording"), emptyList(), 3)
    }.result()
    private val report = AnatomyReport("com.x", "X", "1", 26, 35, "-", emptyList(),
        listOf(AppPermission("android.permission.ACCESS_FINE_LOCATION", "Dangerous"), AppPermission("android.permission.CAMERA", "Dangerous"),
            AppPermission("android.permission.INTERNET", "Normal")), behaviors = behaviors)

    @Test fun matchesRequestedPermissionsWithCode() {
        val use = report.permissionUse().associateBy { it.permission.substringAfterLast('.') }
        assertEquals(setOf("ACCESS_FINE_LOCATION", "CAMERA"), use.keys)
        assertTrue(use.getValue("ACCESS_FINE_LOCATION").seen)
        assertEquals(1, use.getValue("ACCESS_FINE_LOCATION").inAppPackage)
        assertFalse(use.getValue("CAMERA").seen)
        assertEquals("Membuka kamera", use.getValue("CAMERA").behaviorTitle)
    }

    @Test fun findsApisWithoutTheirPermission() {
        val undeclared = report.undeclaredUse().single()
        assertEquals("Merekam audio", undeclared.behaviorTitle)
        assertEquals(listOf("android.permission.RECORD_AUDIO"), undeclared.permissions)
        assertEquals(listOf("base.apk:classes.dex:Llib/Rec;->go()V"), undeclared.usedBy)
    }
}
