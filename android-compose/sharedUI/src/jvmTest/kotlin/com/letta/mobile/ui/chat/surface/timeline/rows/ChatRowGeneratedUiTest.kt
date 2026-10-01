@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-2don7, ported from desktop's DesktopGeneratedUiCardTest at the bglj6.1 cutover:
 * a generated-UI tool result renders through the real A2UI renderer when the payload adapts to a
 * widget, and falls back to its text, never a blank card, when it does not.
 */
class ChatRowGeneratedUiTest {

    @Test
    fun aRecognizedWidgetRendersTheA2uiComponentNotRawJson() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GeneratedUiCard(
                    UiGeneratedComponent(
                        name = "Text",
                        propsJson = """{"text":"Hello from A2UI"}""",
                        fallbackText = "Hello from A2UI (fallback)",
                    ),
                    rowContext(),
                    rowCallbacks(),
                )
            }
        }

        onNodeWithText("Hello from A2UI").assertExists()
    }

    @Test
    fun aRecognizedWidgetsActionReachesTheChatActions() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent {
            MaterialTheme {
                GeneratedUiCard(
                    UiGeneratedComponent(
                        name = "Button",
                        propsJson = """{"label":"Continue","action":{"name":"example.continue"}}""",
                    ),
                    rowContext(),
                    rowCallbacks(actions = actions),
                )
            }
        }

        onNodeWithText("Continue").performClick()
        assertEquals(listOf("submitA2uiAction"), actions.calls)
    }

    @Test
    fun anUnrecognizedWidgetFallsBackToItsText() = runComposeUiTest {
        val fallback = "Shared render model, tool call contracts, and A2UI payload surface are available."
        setContent {
            MaterialTheme {
                GeneratedUiCard(
                    UiGeneratedComponent(
                        name = "DesktopReadinessCard",
                        propsJson = """{"catalog":"basic","status":"preview"}""",
                        fallbackText = fallback,
                    ),
                    rowContext(),
                    rowCallbacks(),
                )
            }
        }

        onNodeWithText(fallback).assertExists()
    }

    @Test
    fun unparseablePropsFallBackToTheText() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GeneratedUiCard(
                    UiGeneratedComponent(
                        name = "Text",
                        propsJson = "not valid json",
                        fallbackText = "Fallback wins when props can't parse",
                    ),
                    rowContext(),
                    rowCallbacks(),
                )
            }
        }

        onNodeWithText("Fallback wins when props can't parse").assertExists()
    }
}
