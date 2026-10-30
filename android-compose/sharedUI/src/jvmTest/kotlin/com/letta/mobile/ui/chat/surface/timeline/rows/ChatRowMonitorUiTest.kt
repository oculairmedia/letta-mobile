@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiSubagentNotification
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatRowMonitorUiTest {
    @Test
    fun screenshotBatchRendersReadableLines() = runComposeUiTest {
        val raw = """
            New PR comment from @github-actions: <!-- 0 failure: 1 warning: PR touches web/de... DangerID: danger-id-danger/kotlin; --> <table> <thead> <tr> <th width="50"></th> <th width="100%" data-danger-table="true">Warnings</th> </tr> </thead> <tbody><tr> <td>:warning:</td> <td>PR to… https://github.com/oculairmedia/letta-mobile/pull/2281#issuecomment-6515332548
            New commented review from @codescene-access: [//]: # (cs-code-health) **Gates Failed** [![](https://codescene.io/imgs/svg/x1.svg)](#) New code is healthy `(1 new file with code health below 10.00)` [![](https://codescene.io/imgs/svg/x1.svg)](#) Enforce advisory code health rules `(2 f… https://github.com/oculairmedia/letta-mobile/pull/2281#pullrequestreview-5708244648
            New inline review comment from @codescene-access on android-compose/sharedLogic/src/jvmAndAndroid/kotlin/com/letta/mobile/data/controller/node/iroh/ConversationAdminHandlers.kt:135: [//]: # (cs-code-health) ❌ New issue: [**Complex Method**](https://codescene.io/projects/82362/delta?repo-id=1664655&review-id=2281&biomarker=Complex+Method&filename=android-compose%2FsharedLogic%2Fsrc%2FjvmAndAndroid%2Fkotlin%2Fcom%2Fletta… https://github.com/oculairmedia/letta-mobile/pull/2281#discussion_r442057280...(truncated)
            Check failed: CodeScene Code Health Review (main) (FAILURE). https://codescene.io/projects/82362/delta/results/8200841
        """.trimIndent()
        setContent {
            MaterialTheme {
                MonitorRow(
                    UiSubagentNotification("running", "Watch PR #2281", "<event>$raw</event>", "events: 2", null, "monitor_1"),
                    watcher = true,
                )
            }
        }
        onNodeWithText("Watch PR #2281", useUnmergedTree = true).performClick()
        val displayed = onAllNodesWithText("Check failed:", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().last().config[SemanticsProperties.Text].joinToString("\n") { it.text }
        assertTrue(displayed.contains("0 failure, 1 warning"))
        assertTrue(displayed.contains("Gates Failed"))
        assertTrue(displayed.contains("Complex Method"))
        assertTrue(displayed.contains("ConversationAdminHandlers.kt:135"))
        assertTrue(displayed.contains("\nCheck failed: CodeScene Code Health Review (main)"))
        listOf("<!--", "<table", "[//]:", "![](", "](", "**", "https://").forEach {
            assertFalse(displayed.contains(it), "Unexpected $it in expanded monitor")
        }
    }

    @Test
    fun expandedWatcherCleansPayloadAndOffersOriginal() = runComposeUiTest {
        val raw = "New commented review from @codescene-access: [//]: # (cs-code-health) **Gates Failed** " +
            "[![](https://codescene.io/imgs/svg/x1.svg)](#) New code is healthy " +
            "https://github.com/oculairmedia/letta-mobile/pull/2281#pullrequestreview-5708244648"
        setContent {
            MaterialTheme {
                MonitorRow(
                    UiSubagentNotification("running", "Watch PR #2281", "<event>$raw</event>", "events: 2", null, "monitor_1"),
                    watcher = true,
                )
            }
        }
        onNodeWithText("Watch PR #2281", useUnmergedTree = true).performClick()
        // Independent literal oracle: must fail on rendering, not a missing cleanup helper.
        onAllNodesWithText(
            "New commented review from @codescene-access: Gates Failed New code is healthy github.com",
            useUnmergedTree = true,
        ).assertCountEquals(2)
        onNodeWithText(raw).assertDoesNotExist()
        onNodeWithText("Show raw").performClick()
        onNodeWithText(raw).assertIsDisplayed()
    }
}
