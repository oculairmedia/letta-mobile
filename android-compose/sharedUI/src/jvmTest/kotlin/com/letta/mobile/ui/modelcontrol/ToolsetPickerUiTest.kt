@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.repository.modelcontrol.ModelControlSession
import com.letta.mobile.data.repository.modelcontrol.ModelPickerBinding
import com.letta.mobile.data.repository.modelcontrol.ModelPickerSource
import com.letta.mobile.data.toolsets.ToolsetOption
import com.letta.mobile.data.toolsets.ToolsetRuntime
import com.letta.mobile.data.toolsets.ToolsetState
import com.letta.mobile.ui.context.ModelPickerHost
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

class ToolsetPickerUiTest {
    @Test
    fun theSharedPickerShowsHostChoicesAndFollowsTheEcho() = runComposeUiTest {
        val runtime = ToolsetRuntime("agent-1", "conv-1")
        val hostState = ToolsetState(listOf(ToolsetOption("auto", "Automatic"), ToolsetOption("codex", "Codex")), "auto")
        val statuses = MutableStateFlow(mapOf(runtime to hostState))
        val calls = mutableListOf<String>()
        val session = ModelControlSession(
            AdminRpcInvoker { method, _ -> calls += method; null }, oauthHttp = null, toolsetStates = { statuses },
        )
        val picker = ModelPickerBinding(
            source = ModelPickerSource.of(MutableStateFlow(listOf(pickerModel("openai/a", "Model A")))) {},
            onModelSelected = {}, onEffortSelected = null, editor = null, onAddProvider = null, onModelsEdited = {},
        ).withToolsets { scope -> session.toolsets(scope, runtime) }
        val host = LettaHost.Touch.copy(sheet = { _, content -> Column { content() } }, dialog = { _, content -> Column { content() } })
        setContent { MaterialTheme { ProvideLettaHost(host) { ModelPickerHost(picker, "openai/a") } } }
        picker.open()
        onNodeWithTag("toolset_chip_auto").assertTextContains("Automatic").assertIsSelected()
        onNodeWithTag("toolset_chip_codex").assertTextContains("Codex").assertIsNotSelected().performClick()
        onNodeWithTag("toolset_chip_codex").performClick()
        runOnIdle { assertEquals(listOf("toolset.update"), calls) }
        onNodeWithTag("toolset_chip_auto").assertIsSelected()
        runOnIdle { statuses.value = mapOf(runtime to hostState.copy(preference = "codex")) }
        onNodeWithTag("toolset_chip_codex").assertIsSelected()
        onNodeWithTag("toolset_chip_auto").assertIsNotSelected()
        runOnIdle { statuses.value = mapOf(runtime to hostState.copy(preference = null)) }
        onNodeWithTag("toolset_chip_auto").assertIsNotSelected()
        onNodeWithTag("toolset_chip_codex").assertIsNotSelected()
    }

    @Test
    fun hostRefusalIsVisibleAndTheCurrentPreferenceStaysSelected() = runComposeUiTest {
        val state = ToolsetState(listOf(ToolsetOption("auto", "Auto"), ToolsetOption("codex", "Codex")), "auto")
        val runtime = ToolsetRuntime("agent-1", "conv-1")
        val statuses = MutableStateFlow(mapOf(runtime to state))
        val session = ModelControlSession(
            AdminRpcInvoker { _, _ -> error("Host refused toolset") }, oauthHttp = null, toolsetStates = { statuses },
        )
        val picker = ModelPickerBinding(
            source = ModelPickerSource.of(MutableStateFlow(listOf(pickerModel("openai/a", "Model A")))) {},
            onModelSelected = {}, onEffortSelected = null, editor = null, onAddProvider = null, onModelsEdited = {},
        ).withToolsets { scope -> session.toolsets(scope, runtime) }
        val host = LettaHost.Touch.copy(sheet = { _, content -> Column { content() } }, dialog = { _, content -> Column { content() } })
        setContent { MaterialTheme { ProvideLettaHost(host) { ModelPickerHost(picker, "openai/a") } } }
        picker.open()
        onNodeWithTag("toolset_chip_codex").performClick()
        onNodeWithText("Host refused toolset").assertExists()
        onNodeWithTag("toolset_chip_auto").assertIsSelected()
    }
}
