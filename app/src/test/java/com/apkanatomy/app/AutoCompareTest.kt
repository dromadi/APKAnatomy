package com.apkanatomy.app

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The ViewModel compares a new analysis with the last one of the same package from the history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoCompareTest {
    private fun AnatomyViewModel.awaitIdle() {
        val deadline = System.currentTimeMillis() + 20_000
        do { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } while (state.value.busy && System.currentTimeMillis() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun secondAnalysisIsComparedWithTheFirstUnlessTheUserPickedABaseline() {
        val model = AnatomyViewModel(ApplicationProvider.getApplicationContext())
        model.awaitIdle()
        model.clearHistory(); model.awaitIdle()
        model.inspect(fixtureUri("arsc/resources-demo.apk")); model.awaitIdle()
        assertNull(model.state.value.baseline)

        model.back()
        model.inspect(fixtureUri("arsc/resources-demo.apk")); model.awaitIdle()
        val state = model.state.value
        assertTrue(state.baselineAuto)
        assertEquals("com.example.arsc", state.baseline?.packageName)
        assertTrue(state.info.orEmpty(), state.info.orEmpty().startsWith("Tidak ada perubahan sejak analisis"))

        // A baseline the user chose stays.
        model.keepAsBaseline()
        val chosen = model.state.value.baseline
        model.back()
        model.inspect(fixtureUri("signing/all.apk")); model.awaitIdle()
        assertSame(chosen, model.state.value.baseline)
        assertFalse(model.state.value.baselineAuto)
    }
}
