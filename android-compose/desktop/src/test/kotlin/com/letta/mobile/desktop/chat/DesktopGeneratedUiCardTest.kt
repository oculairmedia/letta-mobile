@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiGeneratedComponent
import kotlin.test.Test

/**
 * letta-mobile-2don7: desktop's `GeneratedUiCard` used to print `fallbackText`
 * plus raw `propsJson` for every A2UI tool result. It now renders through the
 * real A2UI Basic-catalog renderer (moved into sharedLogic so desktop can
 * reach it) whenever the payload adapts to a real widget, and only falls back
 * to the old text card when it can't (see `toA2uiSurfaceStateOrNull`). These
 * tests prove both paths render real content — never a silent blank, and no
 * longer a raw-JSON dump for a recognized widget.
 */
class DesktopGeneratedUiCardTest {

    @Test
    fun `recognized widget name renders the actual A2UI component, not raw JSON`() = runComposeUiTest {
        val generatedUi = UiGeneratedComponent(
            name = "Text",
            propsJson = """{"text":"Hello from A2UI"}""",
            fallbackText = "Hello from A2UI (fallback)",
        )

        setContent {
            MaterialTheme {
                GeneratedUiCard(generatedUi)
            }
        }

        onNodeWithText("Hello from A2UI").assertExists()
    }

    @Test
    fun `unrecognized widget name falls back to fallbackText, never a blank card`() = runComposeUiTest {
        // Desktop's preview/demo conversations (DesktopChatModels.kt) use
        // arbitrary human-readable component names ("DesktopReadinessCard")
        // that are not real A2UI Basic-catalog widget ids, so the adapter
        // returns null and the card must show fallbackText instead.
        val generatedUi = UiGeneratedComponent(
            name = "DesktopReadinessCard",
            propsJson = """{"catalog":"basic","status":"preview"}""",
            fallbackText = "Shared render model, tool call contracts, and A2UI payload surface are available.",
        )

        setContent {
            MaterialTheme {
                GeneratedUiCard(generatedUi)
            }
        }

        onNodeWithText("Shared render model, tool call contracts, and A2UI payload surface are available.")
            .assertExists()
    }

    @Test
    fun `unparseable props json falls back to fallbackText`() = runComposeUiTest {
        val generatedUi = UiGeneratedComponent(
            name = "Text",
            propsJson = "not valid json",
            fallbackText = "Fallback wins when props can't parse",
        )

        setContent {
            MaterialTheme {
                GeneratedUiCard(generatedUi)
            }
        }

        onNodeWithText("Fallback wins when props can't parse").assertExists()
    }
}
