package com.apkanatomy.axml

import org.junit.Assert.*
import org.junit.Test

/** Fixtures and how they were built: resources/arsc/README.md. Expected values match `aapt2 dump resources`. */
class ResourceTableTest {
    private fun table(name: String) = ResourceTable.decode(javaClass.classLoader.getResourceAsStream("arsc/$name.arsc")!!.readBytes())

    /** [fillerConfig]: aapt2 drops the redundant -v28 qualifier when minSdk is already 35 (compact fixture). */
    private fun check(table: ResourceTable, fillerConfig: String = "in-v28") {
        assertEquals("com.example.arsc", table.packages.single().name)
        assertEquals("string/app_name", table.name(0x7f060000))
        assertEquals("Anatomi Demo", table.value(0x7f060000))
        assertEquals(listOf("" to "Anatomi Demo", "in" to "Demo Anatomi"), table.values(0x7f060000))
        assertEquals("Hello & welcome", table.value(0x7f060015))
        assertEquals(listOf("" to "Filler 7", fillerConfig to "Isian tujuh"), table.values(0x7f060012))
        assertEquals("true", table.value(0x7f010000))
        assertEquals("#ff1b2a3a", table.value(0x7f020000))
        assertEquals("16dip", table.value(0x7f030000))
        assertEquals("3", table.value(0x7f040000))
        assertEquals("res/raw/company_ca.pem", table.value(0x7f050000))
        assertEquals("res/xml/network_security_config.xml", table.value(0x7f070000))
        assertEquals(setOf("in"), table.locales())
        assertEquals(22, table.typeCounts()["string"])
        assertNull(table.name(0x7f060099))
        assertTrue(("string/greeting" to "Hello & welcome") in table.stringValues().toList())
    }

    @Test fun readsDenseTables() = check(table("dense"))
    @Test fun readsSparseTables() = check(table("sparse"))
    @Test fun readsOffset16TablesWithCompactEntries() = check(table("compact"), fillerConfig = "in")

    @Test fun resolvesReferencesInBinaryXml() {
        val nsc = AxmlDecoder.decode(javaClass.classLoader.getResourceAsStream("arsc/network_security_config.axml")!!.readBytes(), table("dense"))
        val anchor = nsc.childElements("domain-config")[1].childElements("trust-anchors").single().childElements("certificates").single()
        assertEquals("@raw/company_ca", anchor.attribute("src", namespaceUri = null))
        assertEquals(0x7f050000, anchor.attributes.single().referenceId)
        val unresolved = AxmlDecoder.decode(javaClass.classLoader.getResourceAsStream("arsc/network_security_config.axml")!!.readBytes())
        assertEquals("@0x7f050000", unresolved.childElements("domain-config")[1].childElements("trust-anchors").single()
            .childElements("certificates").single().attribute("src", namespaceUri = null))
    }

    @Test fun rejectsMalformedTables() {
        val bytes = javaClass.classLoader.getResourceAsStream("arsc/dense.arsc")!!.readBytes()
        assertThrows(AxmlException::class.java) { ResourceTable.decode(ByteArray(4)) }
        for (length in listOf(12, 100, bytes.size / 2, bytes.size - 1)) {
            assertThrows("length $length", AxmlException::class.java) { ResourceTable.decode(bytes.copyOf(length)) }
        }
    }
}
