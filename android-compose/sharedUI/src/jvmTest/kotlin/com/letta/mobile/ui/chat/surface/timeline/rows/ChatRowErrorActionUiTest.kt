@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-bzvro.9 (F09): each actionable run error offers its one action. */
class ChatRowErrorActionUiTest {
    private val prompt = message("u-1", "user", "summarise the repo")

    private fun error(text: String) = UiMessage(id = "e-1", role = "assistant", content = text, timestamp = "2026-10-08T10:00:00Z", isError = true)

    @Test
    fun aRateLimitOffersRetryWhichRerunsTheLastPrompt() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent {
            MaterialTheme {
                ChatMessageRow(
                    error(TurnFailureNotices.messageFor("rate_limited")),
                    rowContext(),
                    callbacks(actions, lastPrompt = prompt),
                )
            }
        }
        onNodeWithTag(ChatRowTestTags.ERROR_ACTION).assertTextEquals("Retry").performClick()
        assertEquals(listOf(prompt), actions.reruns)
    }

    @Test
    fun withoutRerunRetrySendsThePromptTextAgain() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent {
            MaterialTheme {
                ChatMessageRow(
                    error("fetch failed: ECONNRESET"),
                    rowContext(capabilities = ChatSurfaceCapabilities(rerun = false)),
                    callbacks(actions, lastPrompt = prompt),
                )
            }
        }
        onNodeWithTag(ChatRowTestTags.ERROR_ACTION).performClick()
        assertEquals(listOf("summarise the repo"), actions.sent)
    }

    @Test
    fun aCreditLimitOffersTheModelPicker() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme {
                ChatMessageRow(
                    error(TurnFailureNotices.messageFor("credit_limit")),
                    rowContext(),
                    callbacks(RecordingChatActions(), host = ChatSurfaceHost(openModelPicker = { opened++ })),
                )
            }
        }
        onNodeWithTag(ChatRowTestTags.ERROR_ACTION).assertTextEquals("Switch model").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aFullContextWindowPutsCompactInTheDraft() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent {
            MaterialTheme {
                ChatMessageRow(error(TurnFailureNotices.messageFor("context_window_exceeded")), rowContext(), callbacks(actions))
            }
        }
        onNodeWithTag(ChatRowTestTags.ERROR_ACTION).assertTextEquals("Compact").performClick()
        assertEquals(listOf(COMPACT_COMMAND), actions.texts)
    }

    @Test
    fun anUnknownErrorKeepsTheGenericBubble() = runComposeUiTest {
        setContent {
            MaterialTheme { ChatMessageRow(error(TurnFailureNotices.GENERIC_MESSAGE), rowContext(), callbacks(RecordingChatActions())) }
        }
        onNodeWithTag(ChatRowTestTags.ERROR_ACTION).assertDoesNotExist()
    }

    @Test
    fun switchModelIsHiddenWhenThePageCannotOpenThePicker() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatMessageRow(error(TurnFailureNotices.messageFor("model_not_supported")), rowContext(), callbacks(RecordingChatActions()))
            }
        }
        onNodeWithTag(ChatRowTestTags.ERROR_ACTION).assertDoesNotExist()
    }

    private fun callbacks(
        actions: RecordingChatActions,
        host: ChatSurfaceHost = ChatSurfaceHost(),
        lastPrompt: UiMessage? = null,
    ) = ChatRowCallbacks(actions = actions, host = host, onImageTap = { _, _ -> }, lastPrompt = { lastPrompt })
}
