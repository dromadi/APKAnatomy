package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class GlossaryTest {
    private val tabs = setOf("Ringkasan", "Privasi", "Perilaku", "Teknologi", "Komponen", "Izin", "Kemampuan", "Isi APK", "Kode", "Manifest", "DNA", "Kulit", "Jejak")

    @Test fun termsAreUniqueAndPointToRealTabs() {
        assertEquals(GLOSSARY.size, GLOSSARY.map { it.term.lowercase() }.distinct().size)
        assertTrue(GLOSSARY.size >= 40)
        GLOSSARY.forEach { assertTrue("${it.term} → ${it.tab}", it.tab == null || it.tab in tabs) }
    }

    @Test fun searchRanksNameAndAliasMatchesFirst() {
        val results = GLOSSARY.search("dex")
        assertEquals("DEX", results.first().term)
        assertTrue(results.any { it.explanation.contains("dex", true) && !it.term.contains("dex", true) })
        assertEquals("R8 / ProGuard", GLOSSARY.search("obfuscation").first().term)
        assertEquals(GLOSSARY, GLOSSARY.search("  "))
        assertTrue(GLOSSARY.search("zzzz").isEmpty())
    }
}
