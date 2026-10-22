package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class CapabilitiesTest {
    @Test fun decodesForegroundServiceFlags() {
        assertEquals(listOf("dataSync", "location", "camera"), foregroundTypes(0x49))
        assertEquals(listOf("specialUse"), foregroundTypes(0x40000000))
        assertEquals(listOf("location", "0x1000"), foregroundTypes(0x1008))
        assertEquals(emptyList<String>(), foregroundTypes(0))
    }

    @Test fun flagsTaskHijackingPatternsAndLegacyOptions() {
        val caps = Capabilities(sharedUserId = "com.example.shared", requestLegacyExternalStorage = true,
            foreignTaskAffinities = listOf(TaskAffinityUse("com.example.Hijack", "com.victim.app", allowReparenting = true, exported = true),
                TaskAffinityUse("com.example.Other", "com.example.flow", allowReparenting = false, exported = false)))
        val notes = caps.securityNotes().map { it.level to it.title }
        assertEquals(listOf(NoteLevel.PERHATIAN to "Pola task hijacking (StrandHogg)", NoteLevel.INFO to "taskAffinity ke task lain",
            NoteLevel.INFO to "sharedUserId: com.example.shared", NoteLevel.INFO to "requestLegacyExternalStorage"), notes)
    }

    @Test fun explainsCommonFeatures() {
        assertEquals("Butuh kamera.", featureLesson("android.hardware.camera"))
        assertEquals("Android TV.", featureLesson("android.software.leanback"))
        assertNull(featureLesson("com.vendor.custom"))
    }
}
