@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.AgentMessageProvenance
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-bglj6.1: DesktopAgentMessageProvenanceUiTest's cases against the shared rows.
 * The resolver and agent click that desktop threaded through LocalDesktopAgentMessageContext
 * are ChatRowCallbacks inputs here.
 */
class ChatRowProvenanceUiTest {
    private fun inboundProvenance(
        deliveryState: AgentMessageDeliveryState = AgentMessageDeliveryState.RECEIVER_CONFIRMED,
        failureReason: String? = null,
    ) = AgentMessageProvenance(
        direction = AgentMessageDirection.INBOUND,
        fromAgentId = "agent-meridian",
        toAgentId = "agent-pm-letta-mobile",
        msgId = "msg-1",
        deliveryState = deliveryState,
        failureReason = failureReason,
    )

    private fun inbound(id: String, content: String, provenance: AgentMessageProvenance = inboundProvenance()) =
        UiMessage(
            id = id,
            role = "user",
            content = content,
            timestamp = "2026-08-15T12:00:00Z",
            agentMessageProvenance = provenance,
        )

    @Test
    fun inboundAgentMessageShowsCompactSenderToRecipientLabel() = runComposeUiTest {
        val names = mapOf("agent-meridian" to "Meridian", "agent-pm-letta-mobile" to "PM-letta-mobile")
        setContent {
            MaterialTheme {
                RenderRow(
                    single(inbound("inbound-1", "Deploy finished cleanly.")),
                    callbacks = rowCallbacks(resolveAgentName = { names[it] }),
                )
            }
        }

        onNodeWithText("Meridian", substring = true).assertExists()
        onNodeWithText("PM-letta-mobile", substring = true).assertExists()
        onNodeWithText("Agent message", substring = true).assertExists()
        onNodeWithText("Deploy finished cleanly.").assertExists()
    }

    @Test
    fun unresolvedAgentIdFallsBackToShortIdLabelInsteadOfBlank() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(inbound("inbound-unknown-1", "Hello"))) } }

        onNodeWithText("Agent meridian", substring = true).assertExists()
    }

    @Test
    fun expandingProvenanceLabelRevealsTechnicalMetadata() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(inbound("inbound-2", "Status update"))) } }

        onNodeWithText("agent-meridian").assertDoesNotExist()
        onNodeWithContentDescription("Expand agent message details").performClick()
        onNodeWithText("agent-meridian").assertExists()
        onNodeWithText("agent-pm-letta-mobile").assertExists()
        onNodeWithText("msg-1").assertExists()
        onNodeWithText("iroh").assertExists()
    }

    @Test
    fun failedDeliveryShowsFailureReasonWhenExpanded() = runComposeUiTest {
        val failed = inboundProvenance(
            deliveryState = AgentMessageDeliveryState.FAILED,
            failureReason = "application_input_failure",
        )
        setContent { MaterialTheme { RenderRow(single(inbound("inbound-3", "n/a", failed))) } }

        onNodeWithText("Failed", substring = true).assertExists()
        onNodeWithContentDescription("Expand agent message details").performClick()
        onNodeWithText("application_input_failure").assertExists()
    }

    @Test
    fun clickingSenderOrRecipientNameOpensTheAgentThroughTheHost() = runComposeUiTest {
        var clicked: String? = null
        setContent {
            MaterialTheme {
                RenderRow(
                    single(inbound("inbound-4", "Ping")),
                    callbacks = rowCallbacks(
                        host = ChatSurfaceHost(openAgent = { clicked = it }),
                        resolveAgentName = { if (it == "agent-meridian") "Meridian" else null },
                    ),
                )
            }
        }

        onNodeWithText("Meridian").assertHasClickAction().performClick()
        runOnIdle { assertEquals("agent-meridian", clicked) }
    }

    @Test
    fun inboundAgentMessageIsAccessibleViaContentDescription() = runComposeUiTest {
        setContent { MaterialTheme { RenderRow(single(inbound("inbound-5", "Ping"))) } }

        onNodeWithContentDescription(
            "Agent message, inbound, from Agent meridian to Agent pm-letta, delivered",
        ).assertExists()
    }

    @Test
    fun outboundAgentMessageSendToolCallShowsSenderToRecipientLabel() = runComposeUiTest {
        val outbound = AgentMessageProvenance(
            direction = AgentMessageDirection.OUTBOUND,
            fromAgentId = "agent-pm-letta-mobile",
            toAgentId = "agent-meridian",
            msgId = "msg-2",
            deliveryState = AgentMessageDeliveryState.RECEIVER_CONFIRMED,
        )
        val message = UiMessage(
            id = "outbound-1",
            role = "assistant",
            content = "",
            timestamp = "2026-08-15T12:00:00Z",
            toolCalls = listOf(
                UiToolCall(
                    name = "agent_message_send",
                    arguments = """{"to":"agent-meridian","body":"status update"}""",
                    result = """{"ok":true,"delivered":true,"msgId":"msg-2","to":"agent-meridian"}""",
                    status = "success",
                    toolCallId = "call-1",
                    agentMessageProvenance = outbound,
                ),
            ),
        )
        setContent {
            MaterialTheme {
                RenderRow(
                    single(message),
                    callbacks = rowCallbacks(
                        resolveAgentName = { if (it == "agent-pm-letta-mobile") "PM-letta-mobile" else "Meridian" },
                    ),
                )
            }
        }

        // The send reads as one tool summary line; the card with its sender -> recipient label
        // opens from it.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithText("PM-letta-mobile → Meridian · Agent message").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertExists()
    }
}
