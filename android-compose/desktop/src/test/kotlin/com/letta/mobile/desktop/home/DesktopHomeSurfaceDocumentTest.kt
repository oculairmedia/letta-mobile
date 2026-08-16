@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiGeneratedComponent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-2don7: [DesktopHomeSurface]'s `document` parameter was reserved
 * but ignored — the doc comment spelled out the intent (a Letta Code mod
 * emits an A2UI document that replaces the native dashboard) without a
 * renderer to back it. This proves the standalone (non-chat) A2UI surface
 * host now actually renders a document in place of the native page, and that
 * an unrenderable document still falls back to the native dashboard instead
 * of a blank page.
 */
class DesktopHomeSurfaceDocumentTest {

    private val overview = buildFleetOverview(
        FleetOverviewParams(
            conversations = emptyList(),
            rosterAgents = emptyList(),
        ),
    )

    private val state = DesktopHomeState(
        overview = overview,
        sort = FleetSort(),
        orbIndexByAgentId = emptyMap(),
    )

    private val actions = DesktopHomeActions(
        onSortKeySelected = {},
        onOpenAgent = {},
        onOpenConversation = {},
        onSubmitPrompt = {},
    )

    @Test
    fun `a recognized document replaces the native dashboard with the rendered A2UI page`() = runComposeUiTest {
        val document = UiGeneratedComponent(
            name = "Text",
            propsJson = """{"text":"Mod-authored home page"}""",
            fallbackText = "Mod-authored home page (fallback)",
        )

        setContent {
            MaterialTheme {
                DesktopHomeSurface(state = state, actions = actions, document = document)
            }
        }

        onNodeWithText("Mod-authored home page").assertExists()
        // The native dashboard's own heading must not also be present — the
        // document fully replaces it, it doesn't sit alongside it.
        onNodeWithText("Home").assertDoesNotExist()
    }

    @Test
    fun `standalone document action reaches the desktop home host`() = runComposeUiTest {
        val document = UiGeneratedComponent(
            name = "Button",
            propsJson = """{"label":"Open issue","action":{"name":"issue.open"}}""",
        )
        var actionName: String? = null

        setContent {
            MaterialTheme {
                DesktopHomeSurface(
                    state = state,
                    actions = actions.copy(onA2uiAction = { actionName = it.name }),
                    document = document,
                )
            }
        }

        onNodeWithText("Open issue").performClick()
        assertEquals("issue.open", actionName)
    }

    @Test
    fun `an unrenderable document still shows the native dashboard, never a blank page`() = runComposeUiTest {
        val document = UiGeneratedComponent(
            name = "NotACatalogWidget",
            propsJson = "not valid json",
            fallbackText = "irrelevant",
        )

        setContent {
            MaterialTheme {
                DesktopHomeSurface(state = state, actions = actions, document = document)
            }
        }

        onNodeWithText("Home").assertExists()
    }

    @Test
    fun `no document renders the native dashboard as before`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopHomeSurface(state = state, actions = actions)
            }
        }

        onNodeWithText("Home").assertExists()
    }
}
