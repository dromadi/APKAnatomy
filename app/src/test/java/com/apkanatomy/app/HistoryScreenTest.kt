package com.apkanatomy.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.apkanatomy.data.HistoryStore
import com.apkanatomy.domain.FilePreview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private fun screen(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent {
        MaterialTheme { Surface { Column(Modifier.fillMaxSize().padding(16.dp)) { content() } } }
    }

    /** Every class reachable from a fully populated report must survive the history file. */
    @Test fun richReportRoundTripsAndOpensFromHistory() {
        val store = HistoryStore(temp.root)
        assertTrue(store.save(richReport(), 1_700_000_000_000))
        val entry = store.list().single()
        val loaded = store.load(entry.id)!!
        val expected = richReport().let { it.copy(code = loaded.code, source = loaded.source, iconPng = null) }
        assertEquals(expected, loaded.copy(iconPng = null))
        screen { AnatomyReportScreen(loaded, { FilePreview.Unavailable("—") }) }
        compose.onNodeWithText("Riwayat · ", substring = true).assertExists()
        compose.openTab("Kode")
        compose.onNodeWithText("Laporan dibuka dari riwayat", substring = true).assertExists()
        compose.openTab("Jejak")
        compose.scrollToText("api.contoh.id")
        compose.onNodeWithText("id.contoh.toko.net.Api.checkout() (di luar indeks kode)").assertExists()
        compose.shot("history_report_jejak")
    }

    @Test fun historyListOffersOpenCompareAndDelete() {
        val store = HistoryStore(temp.root)
        store.save(richReport(), 1_700_000_000_000)
        store.save(richReport().copy(version = "4.1.0"), 1_690_000_000_000)
        val opened = mutableListOf<String>()
        val compared = mutableListOf<String>()
        screen { HistoryList(store.list(), busy = false, open = { opened += it }, asBaseline = { compared += it }, delete = {}, clear = {}) }
        compose.onAllNodesWithText("Toko Contoh").assertCountEquals(2)
        compose.onAllNodesWithText("Buka")[0].performClick()
        compose.onAllNodesWithText("Jadikan pembanding")[1].performClick()
        assertEquals(store.list().map { it.id }, opened + compared)
        compose.shot("history_list")
    }

    @Test fun glossarySearches() {
        screen { GlossaryScreen() }
        compose.onNodeWithText("Paket & build").assertExists()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("strandhogg")
        compose.onNodeWithText("Task affinity / StrandHogg").assertExists()
        compose.onNodeWithText("Lihat di tab Kemampuan").assertExists()
        compose.shot("glossary_search")
    }

    @Test fun progressShowsTheStepAndCancels() {
        var cancelled = false
        screen { AnalysisProgress(2) { cancelled = true } }
        compose.onNodeWithText("Langkah 3/7: Mengindeks kode, string & perilaku…").assertExists()
        compose.onNodeWithText("Batal").performClick()
        assertTrue(cancelled)
        compose.shot("analysis_progress")
    }
}
