package com.apkanatomy.data

import com.apkanatomy.axml.AxmlDecoder
import com.apkanatomy.domain.IntentFilterInfo
import org.junit.Assert.*
import org.junit.Test

/** intent_manifest.axml is aapt2 output of intent_manifest.xml next to it. */
class ManifestComponentsTest {
    private val filters by lazy {
        intentFiltersByComponent(AxmlDecoder.decode(javaClass.classLoader!!.getResourceAsStream("intent_manifest.axml")!!.readBytes()))
    }

    @Test fun resolvesNamesLikeThePackageManager() {
        assertEquals(setOf("com.example.intents.MainActivity", "com.example.intents.ShareActivity", "com.example.intents.Alias",
            "com.example.other.ReaderService", "com.example.intents.AdminReceiver", "com.example.intents.SplitActivity"), filters.keys)
        assertEquals("a.b.C", qualify("x", "a.b.C"))
    }

    @Test fun readsActionsCategoriesAndData() {
        val main = filters.getValue("com.example.intents.MainActivity")
        assertEquals(listOf("android.intent.action.MAIN"), main[0].actions)
        assertEquals(IntentFilterInfo(listOf("android.intent.action.VIEW"),
            listOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
            schemes = listOf("https"), hosts = listOf("example.com"), paths = listOf("/open*"), autoVerify = true), main[1])
        assertEquals(listOf("image/*"), filters.getValue("com.example.intents.ShareActivity").single().mimeTypes)
        assertEquals(listOf("myapp"), filters.getValue("com.example.intents.Alias").single().schemes)
        // Split <data> elements (scheme in one, host and port in another) belong to one filter.
        assertEquals(listOf("https"), filters.getValue("com.example.intents.SplitActivity").single().schemes)
        assertEquals(listOf("a.example:8443", "b.example"), filters.getValue("com.example.intents.SplitActivity").single().hosts)
    }

    @Test fun fallsBackToResourceIdsWhenNamesAreStripped() {
        val stripped = AxmlDecoder.decode(javaClass.classLoader!!.getResourceAsStream("intent_manifest.axml")!!.readBytes()).let { root ->
            fun strip(e: com.apkanatomy.axml.XmlElement): com.apkanatomy.axml.XmlElement = e.copy(
                attributes = e.attributes.map { if (it.resourceId != null) it.copy(name = "attr_0x%08x".format(it.resourceId)) else it },
                children = e.children.map { if (it is com.apkanatomy.axml.XmlElement) strip(it) else it })
            strip(root)
        }
        assertEquals(filters, intentFiltersByComponent(stripped))
    }
}
