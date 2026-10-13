package com.apkanatomy.domain
import org.junit.Assert.*
import org.junit.Test
class ComponentFilterTest {
    private val components = listOf(
        AppComponent("com.example.PublicActivity", ComponentKind.ACTIVITY, true, true, null),
        AppComponent("com.example.PrivateService", ComponentKind.SERVICE, false, true, null))
    @Test fun combinesNameAndExportedFilters() {
        assertEquals(1, components.filtered("PUBLIC", true).size)
        assertTrue(components.filtered("Private", true).isEmpty())
    }
    @Test fun includesPrivateWhenFilterOff() { assertEquals(2, components.filtered("", false).size) }
}
