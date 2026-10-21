package com.apkanatomy.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.apkanatomy.data.AnatomyRepository
import com.apkanatomy.domain.AnatomyReport
import com.apkanatomy.domain.SignatureScheme
import com.apkanatomy.domain.asBaseline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Runs the real repository (PackageManager, every scanner, bundle import) on Robolectric against
 * the data module's fixture APKs, then renders and screenshots the resulting screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RealAnalysisScreenTest {
    @get:Rule val compose = createComposeRule()
    private val repository = AnatomyRepository(ApplicationProvider.getApplicationContext())

    @After fun release() = repository.release()

    private fun analyze(name: String): AnatomyReport = runBlocking { repository.analyzeFile(fixtureUri(name)) }

    private fun show(report: AnatomyReport, baseline: AnatomyReport? = null) = compose.setContent {
        MaterialTheme { Column(Modifier.fillMaxSize().padding(16.dp)) { AnatomyReportScreen(report, { repository.preview(it.archive, it.path) }, baseline) } }
    }

    @Test fun resourceDemoShowsResolvedNamesNetworkConfigAndPreviews() {
        val report = analyze("arsc/resources-demo.apk")
        assertEquals("com.example.arsc", report.packageName)
        assertEquals("res/xml/network_security_config.xml", report.networkSecurity?.file)
        show(report)
        compose.shot("real_overview")
        compose.openTab("DNA")
        // The NSC card is below the audit notes, which also mention the config.
        compose.scrollToText("base-config: cleartext dilarang")
        compose.shot("real_dna_nsc")
        compose.openTab("Manifest")
        compose.onNodeWithText("@string/app_name", substring = true).assertExists()
        compose.shot("real_manifest")
        compose.openTab("Isi APK")
        compose.onNodeWithText("Folder: res/").performClick()
        compose.onNodeWithText("Folder: xml/").performClick()
        compose.onNodeWithText("network_security_config.xml").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("XML biner (didekode)")).fetchSemanticsNodes().isNotEmpty() }
        compose.shot("real_preview_xml")
        // The preview text is one node, so this checks the dialog scrolls to it, not that a given line is on screen.
        compose.onNodeWithText("@raw/company_ca", substring = true).performScrollTo().assertIsDisplayed()
        compose.shot("real_preview_xml_scrolled")
        compose.onNodeWithText("Tutup").performClick()
        compose.openTab("Kulit")
        compose.scrollToText("Resource & bahasa")
        compose.shot("real_kulit")
    }

    @Test fun signedApkShowsSchemesAndCertificate() {
        val report = analyze("signing/all.apk")
        assertEquals(setOf(SignatureScheme.V1, SignatureScheme.V2, SignatureScheme.V3), report.signing.schemes)
        show(report)
        compose.openTab("DNA")
        compose.scrollToText("CN=APKAnatomy Test")
        compose.onNodeWithText("92c5497525771de7cc0c5c8bdbbb3248c0e041c2741f217e4ba40bda74c8da0b", substring = true).assertExists()
        compose.shot("real_dna_certificate")
    }

    @Test fun xapkBundleIsUnpackedAndItsMismatchedSplitFlagged() {
        val report = analyze("bundle/demo.xapk")
        assertEquals("XAPK (3 APK)", report.source)
        assertEquals(listOf("config.xxhdpi.apk"), report.signing.mismatchedSplits)
        assertEquals(listOf("base.apk", "config.arm64_v8a.apk", "config.xxhdpi.apk"), report.structure.archives)
        show(report)
        compose.openTab("DNA")
        compose.onNodeWithText("Split dengan penandatangan berbeda", substring = true).assertExists()
        compose.shot("real_bundle_dna")
        compose.openTab("Isi APK")
        compose.onNodeWithText("config.arm64_v8a.apk").performClick()
        compose.shot("real_bundle_archives")
    }

    @Test fun comparisonTabListsChangesBetweenTwoRealAnalyses() {
        val old = analyze("signing/all.apk").asBaseline()
        val new = analyze("arsc/resources-demo.apk")
        show(new, baseline = old)
        compose.openTab("Banding")
        compose.onNodeWithText("Package: com.example.signed → com.example.arsc", substring = true).assertExists()
        compose.shot("real_banding")
    }
}
