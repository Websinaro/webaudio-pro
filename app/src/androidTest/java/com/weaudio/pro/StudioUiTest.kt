package com.weaudio.pro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class StudioUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun startupShowsStudioWorkspace() {
        rule.onNodeWithText("WE AUDIO PRO").assertIsDisplayed()
        rule.onNodeWithText("Untitled Project").assertIsDisplayed()
        rule.onNodeWithText("NOT SAVED").assertIsDisplayed()
        listOf("transport_restart", "transport_play", "transport_pause", "transport_stop", "engine_status")
            .forEach { rule.onNodeWithTag(it).assertIsDisplayed() }
        rule.onNodeWithText("Arrangement is empty").assertIsDisplayed()
    }

    @Test fun addLayerIsDisabledAndMarkedFuture() {
        rule.onNodeWithText("Available in Phase 3").assertIsDisplayed()
        rule.onNodeWithText("+ ADD LAYER").assertIsDisplayed()
    }

    @Test fun pauseAndStopAreDisabledBeforePlayback() {
        rule.onNodeWithTag("transport_pause").assertIsNotEnabled()
    }

    @Test fun bottomNavigationReachesEveryDestination() {
        rule.onNodeWithTag("nav_projects").performClick()
        rule.onNodeWithText("CURRENT PROJECT").assertIsDisplayed()
        rule.onNodeWithTag("nav_mixer").performClick()
        rule.onNodeWithText("Coming in Phase 4").assertIsDisplayed()
        rule.onNodeWithTag("nav_sounds").performClick()
        rule.onNodeWithText("Coming in Phase 2").assertIsDisplayed()
        rule.onNodeWithTag("nav_studio").performClick()
        rule.onNodeWithText("Arrangement is empty").assertIsDisplayed()
    }

    @Test fun settingsOpensDiagnosticsAndBackReturns() {
        rule.onNodeWithTag("settings_button").performClick()
        rule.onNodeWithText("Audio settings & diagnostics").assertIsDisplayed()
        rule.onNodeWithTag("diag_status").assertIsDisplayed()
        rule.onNodeWithTag("back_button").performClick()
        rule.onNodeWithText("Untitled Project").assertIsDisplayed()
    }
}
