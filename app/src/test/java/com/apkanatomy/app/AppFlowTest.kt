package com.apkanatomy.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Starts the real MainActivity with its ViewModel and the package manager Robolectric provides. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun installedAppListLoads() {
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("aplikasi", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pelajari cara aplikasi dibangun").assertExists()
        compose.onNodeWithText("Buka APK / bundle").assertExists()
        compose.shot("app_list")
    }
}
