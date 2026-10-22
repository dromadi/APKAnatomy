package com.apkanatomy.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
        for ((tab, marker) in listOf("Teknologi" to "Google AdMob", "Komponen" to "PaymentActivity", "Izin" to "SYSTEM_ALERT_WINDOW",
            "Isi APK" to "classes.dex", "Kode" to "Cari class", "Manifest" to "android:label", "DNA" to "Catatan audit", "Kulit" to "Kode DEX", "Jejak" to "Midtrans server key")) {
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

    @Test fun darkThemeRenders() {
        show(dark = true)
        compose.openTab("DNA")
        compose.shot("rich_dna_dark")
    }
}
