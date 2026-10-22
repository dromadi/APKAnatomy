package com.apkanatomy.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.*
import org.junit.Assert.assertEquals
import com.apkanatomy.domain.toHtml
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every tab of a fully populated report; screenshots are reviewed by hand for layout problems. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RichReportScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(dark: Boolean = false) = compose.setContent {
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else MaterialTheme.colorScheme) {
            androidx.compose.material3.Surface {
                Column(Modifier.fillMaxSize().padding(16.dp)) { AnatomyReportScreen(richReport(), { FilePreview.Unavailable("—") }) }
            }
        }
    }

    @Test fun everyTabRenders() {
        show()
        compose.shot("rich_overview")
        for ((tab, marker) in listOf("Belajar" to "Siapa pembuatnya?", "Cari" to "Cari apa saja", "Privasi" to "Pelacak (1)", "Perilaku" to "Pelajari perilaku", "Teknologi" to "Google AdMob", "Komponen" to "Pelajari komponen", "Izin" to "SYSTEM_ALERT_WINDOW", "Kemampuan" to "TrackingService",
            "Isi APK" to "classes.dex", "Kode" to "Mulai menelusuri", "Manifest" to "android:label", "DNA" to "Catatan audit", "Kulit" to "Kode DEX", "Jejak" to "Midtrans server key")) {
            compose.openTab(tab)
            compose.onNodeWithText(marker, substring = true, useUnmergedTree = true).assertExists()
            compose.shot("rich_${tab.lowercase().replace(' ', '_')}")
        }
    }

    @Test fun nativeSectionExplainsPageSizeAndJni() {
        show()
        compose.openTab("Teknologi")
        compose.scrollToText("JNI: jembatan")
        compose.onNodeWithText("Belum siap 16 KB page size", substring = true).assertExists()
        compose.shot("rich_native")
    }

    @Test fun jejakLinksToTheMethodThatLoadsAUrl() {
        show()
        compose.openTab("Jejak")
        compose.scrollToText("api.contoh.id")
        compose.onNodeWithText("okhttp3.internal.Probe.run() (di luar indeks kode)").assertExists()
        compose.onNodeWithText("→ id.contoh.toko.net.Api.checkout()").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Instruksi").assertExists()
        // Pseudo-code is shown first; the DEX instructions are one tap away.
        compose.onNodeWithText("v0 = \"https://api.contoh.id/v2/checkout\";").assertExists()
        compose.shot("rich_xref_method")
        compose.onNodeWithText("Instruksi DEX").performClick()
        compose.onNodeWithText("const-string v0", substring = true).assertExists()
    }

    @Test fun perilakuShowsRiskyCodeFirstAndLinksToIt() {
        show()
        compose.openTab("Perilaku")
        compose.onNodeWithText("Perlu perhatian · TrustManager yang menerima semua sertifikat").assertExists()
        compose.onNodeWithText("com.vendor.net.LaxTrust.checkServerTrusted(…) (di luar indeks kode)").assertExists()
        compose.shot("rich_perilaku")
        compose.scrollToText("→ id.contoh.toko.net.Api.checkout()")
        compose.onAllNodes(androidx.compose.ui.test.hasText("→ id.contoh.toko.net.Api.checkout()"))[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Instruksi").assertExists()
    }

    @Test fun componentOpensItsClassInTheCodeTab() {
        show()
        compose.openTab("Komponen")
        compose.scrollToText("Buka kode PaymentActivity →")
        compose.onNodeWithText("Buka kode PaymentActivity →").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("id.contoh.toko.checkout.PaymentActivity").assertExists()
        compose.onNodeWithText("Lid/contoh/toko/checkout/PaymentActivity;->onCreate(Landroid/os/Bundle;)V").assertExists()
        compose.shot("rich_component_class")
        compose.openTab("Komponen")
        compose.scrollToText("id.contoh.toko.MainActivity")
        compose.onAllNodes(androidx.compose.ui.test.hasText("Class ini tidak ada", substring = true)).fetchSemanticsNodes().let { assert(it.isNotEmpty()) }
    }

    @Test fun callPathLeadsFromTheComponentToTheMethod() {
        show()
        compose.openTab("Jejak")
        compose.scrollToText("api.contoh.id")
        compose.onNodeWithText("→ id.contoh.toko.net.Api.checkout()").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jalur").performScrollTo().performClick()
        compose.onNodeWithText("Jalur 1 · 2 langkah").assertExists()
        compose.onNodeWithText("id.contoh.toko.checkout.PaymentActivity.onCreate(…) · activity").assertExists()
        compose.shot("rich_call_path")
        compose.onNodeWithText("id.contoh.toko.checkout.PaymentActivity.onCreate(…) · activity").performClick()
        compose.onNodeWithText("Lid/contoh/toko/checkout/PaymentActivity;->onCreate(Landroid/os/Bundle;)V").assertExists()
    }

    @Test fun methodShowsItsControlFlowDiagram() {
        fun ins(offset: Int, text: String, kind: FlowKind = FlowKind.NEXT, vararg targets: Int, keys: List<Int> = emptyList()) =
            FlowInstruction(offset, 1, "0x${offset.toString(16)}  $text", kind, targets.toList(), keys)
        val flow = controlFlow(listOf(
            ins(0, "const/4 v0 · literal=0"),
            ins(1, "if-ge v0, p1 · target=0x5", FlowKind.BRANCH, 5),
            ins(2, "invoke-virtual {p0}, Lid/contoh/toko/net/Api;->ping()V"), ins(3, "add-int/lit8 v0, v0"),
            ins(4, "goto · target=0x1", FlowKind.GOTO, 1),
            ins(5, "packed-switch v0 · target=0x10", FlowKind.SWITCH, 7, 8, 7, keys = listOf(0, 1, 2)),
            ins(6, "return-void", FlowKind.RETURN),
            ins(7, "invoke-static {}, Lid/contoh/toko/net/Api;->checkout()V"),
            ins(8, "new-instance v1, Ljava/lang/IllegalStateException;"), ins(9, "throw v1", FlowKind.THROW)),
            listOf(FlowTry(7, 8, listOf("Ljava/io/IOException;" to 6))))
        var asked: String? = null
        compose.setContent {
            MaterialTheme { androidx.compose.material3.Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
                AnatomyReportScreen(richReport(), { FilePreview.Unavailable("—") }, loadFlow = { asked = it; flow })
            } } }
        }
        compose.openTab("Jejak")
        compose.scrollToText("api.contoh.id")
        compose.onNodeWithText("→ id.contoh.toko.net.Api.checkout()").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Alur").performClick()
        compose.waitForIdle()
        assertEquals("Lid/contoh/toko/net/Api;->checkout()V", asked?.substringAfter("classes.dex:"))
        compose.onNodeWithText("7 blok · kompleksitas 4 · 1 perulangan").assertExists()
        compose.onNodeWithText("jika benar → B3").assertExists()
        compose.onNodeWithText("lompat (goto) → B1 ↺ kembali (perulangan)").assertExists()
        compose.onNodeWithText("case 0, 2 → B5").assertExists()
        compose.shot("rich_control_flow")
    }

    @Test fun dnaWarnsWhenAFileIsSignedByAnotherKeyThanTheInstalledApp() {
        compose.setContent {
            MaterialTheme { androidx.compose.material3.Surface { Column(Modifier.fillMaxSize().padding(16.dp)) {
                AnatomyReportScreen(richReport().copy(source = "File APK lokal", versionCode = 420,
                    installedCopy = com.apkanatomy.domain.InstalledCopy("4.1.0", 410, listOf("ff00"), installer = "com.android.vending")), { FilePreview.Unavailable("—") })
            } } }
        }
        compose.openTab("DNA")
        compose.onNodeWithText("Perlu perhatian · Penandatangan berbeda dari aplikasi terpasang").assertExists()
        compose.onNodeWithText("Info · Versi file lebih baru dari yang terpasang").assertExists()
        compose.shot("rich_dna_authenticity")
    }

    @Test fun globalSearchFindsCodeAndOpensIt() {
        show()
        compose.openTab("Cari")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("checkout")
        compose.onNodeWithText("Kode (4)").assertExists()
        compose.shot("rich_search")
        compose.onAllNodes(androidx.compose.ui.test.hasText("method · ", substring = true))[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Instruksi").assertExists()
    }

    /** Writes the HTML export next to the screenshots so CI uploads it for review. */
    @Test fun htmlExportIsWrittenForReview() {
        val html = richReport().toHtml(1_760_000_000_000)
        java.io.File("build/outputs/roborazzi").apply { mkdirs() }.resolve("rich_report.html").writeText(html)
        org.junit.Assert.assertTrue(html.contains("<h2>Perilaku</h2>"))
    }

    @Test fun technologyShowsTheCodeMap() {
        show()
        compose.openTab("Teknologi")
        compose.onNodeWithText("Peta kode").assertExists()
        compose.onNodeWithText("Kode aplikasi sendiri: sekitar 8%", substring = true).assertExists()
        compose.shot("rich_code_map")
    }

    @Test fun permissionsAreMatchedWithCode() {
        show()
        compose.openTab("Izin")
        compose.onNodeWithText("Izin ↔ kode").assertExists()
        compose.onNodeWithText("Info · CAMERA: pemakaian tidak terlihat").assertExists()
        compose.shot("rich_permission_use")
    }

    @Test fun tourWalksThroughTheReport() {
        show()
        compose.openTab("Belajar")
        compose.onNodeWithText("Langkah 1 dari 8 · 0 selesai").assertExists()
        compose.onNodeWithText("Pikirkan dulu, lalu lihat jawaban").performClick()
        compose.onNodeWithText("Langkah 1 dari 8 · 1 selesai").assertExists()
        compose.shot("rich_tour")
        compose.onNodeWithText("Berikutnya").performClick()
        compose.onNodeWithText("Pintu masuk aplikasi").assertExists()
        compose.onNodeWithText("Lihat di tab Komponen →").performClick()
        compose.onNodeWithText("Pelajari komponen").assertExists()
    }

    @Test fun componentsCanBeTriedOut() {
        val started = mutableListOf<android.content.Intent>()
        compose.setContent {
            MaterialTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalIntentLauncher provides { _, intent -> started += intent }) {
                    androidx.compose.material3.Surface { Column(Modifier.fillMaxSize().padding(16.dp)) { AnatomyReportScreen(richReport(), { FilePreview.Unavailable("—") }) } }
                }
            }
        }
        compose.openTab("Komponen")
        compose.onNodeWithText("Coba https://shop.example.com/produk").performScrollTo().performClick()
        org.junit.Assert.assertEquals(android.content.Intent.ACTION_VIEW, started.single().action)
        org.junit.Assert.assertEquals("https://shop.example.com/produk" to "id.contoh.toko", started.single().dataString to started.single().`package`)
        compose.onAllNodes(androidx.compose.ui.test.hasText("Coba buka"))[0].performScrollTo().performClick()
        org.junit.Assert.assertEquals("id.contoh.toko.MainActivity", started.last().component?.className)
        compose.onAllNodes(androidx.compose.ui.test.hasText("dibuka", substring = true)).fetchSemanticsNodes().let { org.junit.Assert.assertTrue(it.isNotEmpty()) }
        compose.shot("rich_try_component")
    }

    @Test fun codeTabSearchesStringLiterals() {
        show()
        compose.openTab("Kode")
        compose.onNodeWithText("String").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("checkout")
        compose.onNodeWithText("Dimuat oleh id.contoh.toko.net.Api.checkout()").assertExists()
        compose.shot("rich_string_search")
    }

    @Test fun darkThemeRenders() {
        show(dark = true)
        compose.openTab("DNA")
        compose.shot("rich_dna_dark")
    }
}
