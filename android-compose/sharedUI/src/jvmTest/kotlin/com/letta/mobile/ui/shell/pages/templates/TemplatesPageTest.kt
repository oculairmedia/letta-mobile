@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.pages.templates

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.templates.AgentTemplatesState
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.2.5 (S17): the shared Templates page - list, empty, creating, error. */
class TemplatesPageTest {
    private fun ComposeUiTest.show(state: AgentTemplatesState, actions: TemplatesPageActions = TemplatesPageActions()) {
        setContent {
            MaterialTheme {
                Box(Modifier.width(420.dp).height(800.dp)) { TemplatesPage(state, actions) }
            }
        }
    }

    @Test
    fun everyStarterHasACreateButton() = runComposeUiTest {
        val created = mutableListOf<String>()
        show(AgentTemplatesState(), TemplatesPageActions(onCreate = { created += it }))
        onNodeWithText("Coding Assistant", substring = true).assertExists()
        onNodeWithTag(TemplatesPageTags.create("writer")).performClick()
        assertEquals(listOf("writer"), created)
    }

    @Test
    fun whileOneIsCreatedTheOthersWait() = runComposeUiTest {
        show(AgentTemplatesState(creatingId = "coder"))
        onNodeWithText("Creating…").assertExists()
        onNodeWithTag(TemplatesPageTags.create("default")).assertIsNotEnabled()
    }

    @Test
    fun aFailureIsShownAndDismissed() = runComposeUiTest {
        val calls = mutableListOf<String>()
        show(AgentTemplatesState(error = "no model"), TemplatesPageActions(onDismissError = { calls += "dismiss" }))
        onNodeWithTag(TemplatesPageTags.ERROR).assertExists()
        onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
        onNodeWithTag(TemplatesPageTags.create("default")).assertIsEnabled()
    }

    @Test
    fun aBackendThatCannotCreateDisablesEveryCreateAndSaysWhy() = runComposeUiTest {
        // letta-mobile-by4vm.45: a direct App Server session has no agent_create route.
        show(AgentTemplatesState(createUnavailableReason = "This backend cannot create agents yet."))
        onNodeWithTag(TemplatesPageTags.UNAVAILABLE).assertExists()
        onNodeWithText("This backend cannot create agents yet.").assertExists()
        onNodeWithTag(TemplatesPageTags.create("default")).assertIsNotEnabled()
        onNodeWithTag(TemplatesPageTags.create("writer")).assertIsNotEnabled()
    }

    @Test
    fun noTemplatesSaysSo() = runComposeUiTest {
        show(AgentTemplatesState(templates = emptyList()))
        onNodeWithTag(TemplatesPageTags.LIST.empty).assertExists()
    }
}
