package com.example.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class PcbInspectorUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fullSixScreenInspectionAndSettingsJourney() {
        composeRule.waitForIdle()

        // 1. Dashboard starts with empty Room state and honest empty chart/history
        composeRule.onNodeWithTag("dashboard_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_total_count").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertIsDisplayed()
        composeRule.onNodeWithTag("dashboard_demo_data_badge").assertDoesNotExist()
        composeRule.onNodeWithTag("dashboard_screen")
            .performScrollToNode(hasText("No daily trend data yet"))
        composeRule.onNodeWithText("No daily trend data yet").assertIsDisplayed()

        // 2. Navigate to Inspect via bottom pill bar
        composeRule.onNodeWithTag("nav_inspect").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("inspect_screen").assertIsDisplayed()

        // Start Inspection button must be disabled before image selection
        composeRule.onNodeWithTag("inspect_start_button").assertIsNotEnabled()

        // Load Prototype Sample Board (QFP-100)
        composeRule.onNodeWithTag("inspect_screen")
            .performScrollToNode(hasTestTag("sample_preset_sample_qfp100"))
        composeRule.onNodeWithTag("sample_preset_sample_qfp100").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("inspect_board_name_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("inspect_screen")
            .performScrollToNode(hasTestTag("inspect_start_button"))
        composeRule.onNodeWithTag("inspect_start_button").assertIsEnabled()

        // Run Inspection -> navigates to Results
        composeRule.onNodeWithTag("inspect_start_button").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("results_screen").fetchSemanticsNodes().isNotEmpty()
        }

        // 3. Results Screen verification
        composeRule.onNodeWithTag("results_disposition_badge").assertIsDisplayed()
        composeRule.onNodeWithText("REJECT").assertIsDisplayed()
        composeRule.onNodeWithTag("results_demo_banner").assertIsDisplayed()

        // Toggle Original vs Annotated overlay
        composeRule.onNodeWithTag("results_screen")
            .performScrollToNode(hasTestTag("results_toggle_original"))
        composeRule.onNodeWithTag("results_toggle_original").performClick()
        composeRule.onNodeWithTag("results_toggle_annotated").performClick()

        // Expand provenance & latency details
        composeRule.onNodeWithTag("results_screen")
            .performScrollToNode(hasTestTag("results_provenance_toggle"))
        composeRule.onNodeWithTag("results_provenance_toggle").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("Model Contract").fetchSemanticsNodes().isNotEmpty()
        }

        // 4. Open AI Technical Advisory Screen
        composeRule.onNodeWithTag("results_screen")
            .performScrollToNode(hasTestTag("results_open_advisory_button"))
        composeRule.onNodeWithTag("results_open_advisory_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("advisory_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Authoritative Policy Disposition").assertIsDisplayed()
        composeRule.onNodeWithText("DEMO ADVISORY").assertIsDisplayed()

        // Back to Results, then back to Inspect, then navigate to Dashboard
        composeRule.onNodeWithTag("advisory_back_button").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("results_screen").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("results_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("nav_dashboard").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("dashboard_demo_data_badge").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dashboard_demo_data_badge").assertIsDisplayed()

        // 5. History Screen: search and filter
        composeRule.onNodeWithTag("nav_history").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("history_screen").assertIsDisplayed()
        composeRule.onNodeWithText("QFP-100 Main Logic PCB").assertIsDisplayed()

        composeRule.onNodeWithTag("history_filter_pass").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("history_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("history_filter_all").performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("QFP-100 Main Logic PCB").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("history_search_input").performTextInput("QFP-100")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("QFP-100 Main Logic PCB").assertIsDisplayed()

        // 6. Settings Screen: theme switch, policy toggle, and Clear History
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_theme_dark"))
        composeRule.onNodeWithTag("settings_theme_dark").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_policy_reject_critical"))
        composeRule.onNodeWithTag("settings_policy_reject_critical").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_screen")
            .performScrollToNode(hasTestTag("settings_clear_history_button"))
        composeRule.onNodeWithTag("settings_clear_history_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_confirm_clear_history").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("settings_storage_banner").fetchSemanticsNodes().isNotEmpty()
        }

        // Verify History is back to empty after clear
        composeRule.onNodeWithTag("nav_history").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("history_empty_state").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
