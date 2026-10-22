package com.apkanatomy.domain

import org.junit.Assert.*
import org.junit.Test

class ExampleUrisTest {
    private val view = listOf("android.intent.action.VIEW")
    private val browsable = listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE")

    @Test fun buildsUrisFromSchemesHostsAndPaths() {
        val web = IntentFilterInfo(view, browsable, schemes = listOf("https", "http"), hosts = listOf("toko.id", "*.toko.id"), paths = listOf("/produk*"))
        assertEquals(listOf("https://toko.id/produk", "https://toko.id/produk", "http://toko.id/produk").distinct(), web.exampleUris())
        val custom = IntentFilterInfo(view, browsable, schemes = listOf("tokoapp"), hosts = listOf("bayar"), paths = listOf("pola /.*"))
        assertEquals(listOf("tokoapp://bayar"), custom.exampleUris())
        assertEquals(listOf("tokoapp://"), IntentFilterInfo(view, browsable, schemes = listOf("tokoapp")).exampleUris())
        assertTrue(IntentFilterInfo(listOf("android.intent.action.MAIN"), listOf("android.intent.category.LAUNCHER")).exampleUris().isEmpty())
    }
}
