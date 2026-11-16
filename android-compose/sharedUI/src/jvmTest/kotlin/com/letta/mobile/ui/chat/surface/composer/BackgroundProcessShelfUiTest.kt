@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import com.letta.mobile.data.model.process.ProcessId
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.collections.immutable.toImmutableList

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.ui.chat.surface.setPointerContent
import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundProcessShelfUiTest {
    @Test
    fun confirmedStopKeepsTheRowUntilTheHostRemovesIt() = runComposeUiTest {
        val snapshots = mutableStateOf(listOf(
            AppServerBackgroundProcess("p", type = "monitor", description = "worker"),
            AppServerBackgroundProcess("q", type = "agent_task", description = "reflection"),
        ))
        val stops = mutableListOf<String>()
        val actions = object : ChatActions by RecordingChatActions() {
            override fun stopBackgroundProcess(processId: ProcessId) { stops += processId.value }
        }
        setPointerContent {
            MaterialTheme {
                ChatComposerPanel(ComposerInputs(
                    composer = ChatComposerUiState(backgroundProcesses = snapshots.value.toImmutableList()),
                    actions = actions,
                ))
            }
        }
        onNodeWithText("Background processes", substring = true).assertDoesNotExist()
        onNodeWithTag("process_item_p").assertExists()
        onNodeWithTag("process_item_q").assertExists()
        onNodeWithContentDescription("Stop process p").performClick()
        runOnIdle { assertEquals(emptyList(), stops) }
        onNodeWithText("Stop background process?").assertExists()
        onNodeWithText("Stop", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(listOf("p"), stops) }
        onNodeWithTag("process_item_p").assertExists()
        runOnIdle { snapshots.value = emptyList() }
        onNodeWithTag("background_process_shelf").assertDoesNotExist()
    }
}
