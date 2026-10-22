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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.apkanatomy.domain.FilePreview
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
        for ((tab, marker) in listOf("Privasi" to "Pelacak (1)", "Perilaku" to "Pelajari perilaku", "Teknologi" to "Google AdMob", "Komponen" to "Pelajari komponen", "Izin" to "SYSTEM_ALERT_WINDOW", "Kemampuan" to "TrackingService",
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
        compose.onNodeWithText("const-string v0", substring = true).assertExists()
        compose.shot("rich_xref_method")
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
        compose.onNodeWithText("Jalur").performClick()
        compose.onNodeWithText("Jalur 1 · 2 langkah").assertExists()
        compose.onNodeWithText("id.contoh.toko.checkout.PaymentActivity.onCreate(…) · activity").assertExists()
        compose.shot("rich_call_path")
        compose.onNodeWithText("id.contoh.toko.checkout.PaymentActivity.onCreate(…) · activity").performClick()
        compose.onNodeWithText("Lid/contoh/toko/checkout/PaymentActivity;->onCreate(Landroid/os/Bundle;)V").assertExists()
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
