package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class SdkDetectionTest {
    @Test fun matchesWholePackageSegmentsWithEvidenceAndVersions() {
        val sdks = detectSdks(
            listOf("okhttp3", "okhttp3.internal", "okhttp3x", "retrofit2", "com.google.firebase.messaging", "coilx"),
            mapOf("com.google.firebase:firebase-messaging" to "24.1.0"))
        assertEquals(listOf("OkHttp", "Retrofit", "Firebase Cloud Messaging"), sdks.map { it.name })
        assertEquals(listOf("okhttp3", "okhttp3.internal"), sdks[0].evidence)
        assertEquals(listOf("firebase-messaging 24.1.0"), sdks[2].versions)
    }

    @Test fun separatesOverlappingLibraries() {
        assertEquals(listOf("Hilt"), detectSdks(listOf("dagger.hilt.android"), emptyMap()).map { it.name })
        assertEquals(listOf("Play Integrity"), detectSdks(listOf("com.google.android.play.core.integrity"), emptyMap()).map { it.name })
    }

    @Test fun obfuscationHintNeedsEnoughClasses() {
        assertNull(obfuscationHint(CodeIndex(definedClasses = 10, shortNamedClasses = 10)))
        assertTrue(obfuscationHint(CodeIndex(definedClasses = 100, shortNamedClasses = 60))!!.contains("kemungkinan besar"))
        assertTrue(obfuscationHint(CodeIndex(definedClasses = 100, shortNamedClasses = 20))!!.contains("sebagian"))
        assertNull(obfuscationHint(CodeIndex(definedClasses = 100, shortNamedClasses = 5)))
    }
}
