@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.desktop.chat

import androidx.compose.material3.MaterialTheme
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-slqfp: desktop render coverage for structured inter-agent
 * provenance — compact transcript label, expansion metadata, and
 * accessibility semantics. The provenance MODEL/PROJECTION itself is tested
 * in `sharedLogic` commonTest (AgentMessageProvenanceProjectionTest,
 * TimelineEventToUiMessageTest); this file only covers how it renders.
 */
class DesktopAgentMessageProvenanceUiTest {

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

    @Test
    fun inboundAgentMessageShowsCompactSenderToRecipientLabel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "inbound-1",
                        role = "user",
                        content = "Deploy finished cleanly.",
                        timestamp = "2026-08-15T12:00:00Z",
                        agentMessageProvenance = inboundProvenance(),
                    ),
                    resolveAgentName = { agentId ->
                        when (agentId) {
                            "agent-meridian" -> "Meridian"
                            "agent-pm-letta-mobile" -> "PM-letta-mobile"
                            else -> null
                        }
                    },
                )
            }
        }

        // Spec format: "Meridian → PM-letta-mobile · Agent message" — a
        // proper arrow glyph, sender first, recipient second.
        onNodeWithText("Meridian", substring = true).assertExists()
        onNodeWithText("PM-letta-mobile", substring = true).assertExists()
        onNodeWithText("Agent message", substring = true).assertExists()
        // Body content stays fully readable alongside the label, not buried.
        onNodeWithText("Deploy finished cleanly.").assertExists()
    }

    @Test
    fun unresolvedAgentIdFallsBackToShortIdLabelInsteadOfBlank() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "inbound-unknown-1",
                        role = "user",
                        content = "Hello",
                        timestamp = "2026-08-15T12:00:00Z",
                        agentMessageProvenance = inboundProvenance(),
                    ),
                    // No resolver wired — every agent name is unknown.
                    resolveAgentName = { null },
                )
            }
        }

        // Falls back to a short, non-blank id-derived label rather than
        // rendering an empty/blank sender name.
        onNodeWithText("Agent meridian", substring = true).assertExists()
    }

    @Test
    fun expandingProvenanceLabelRevealsTechnicalMetadata() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "inbound-2",
                        role = "user",
                        content = "Status update",
                        timestamp = "2026-08-15T12:00:00Z",
                        agentMessageProvenance = inboundProvenance(),
                    ),
                )
            }
        }

        // Metadata (full agent ids, msgId, transport, delivery state) is
        // hidden until the compact label is expanded.
        onNodeWithText("agent-meridian").assertDoesNotExist()
        onNodeWithContentDescription("Expand agent message details").performClick()
        onNodeWithText("agent-meridian").assertExists()
        onNodeWithText("agent-pm-letta-mobile").assertExists()
        onNodeWithText("msg-1").assertExists()
        onNodeWithText("iroh").assertExists()
    }

    @Test
    fun failedDeliveryShowsFailureReasonWhenExpanded() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "inbound-3",
                        role = "user",
                        content = "n/a",
                        timestamp = "2026-08-15T12:00:00Z",
                        agentMessageProvenance = inboundProvenance(
                            deliveryState = AgentMessageDeliveryState.FAILED,
                            failureReason = "application_input_failure",
                        ),
                    ),
                )
            }
        }

        onNodeWithText("Failed", substring = true).assertExists()
        onNodeWithContentDescription("Expand agent message details").performClick()
        onNodeWithText("application_input_failure").assertExists()
    }

    @Test
    fun clickingSenderOrRecipientNameFiresAgentClickCallback() = runComposeUiTest {
        var clicked: String? = null
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "inbound-4",
                        role = "user",
                        content = "Ping",
                        timestamp = "2026-08-15T12:00:00Z",
                        agentMessageProvenance = inboundProvenance(),
                    ),
                    resolveAgentName = { agentId -> if (agentId == "agent-meridian") "Meridian" else null },
                    onAgentClick = { clicked = it },
                )
            }
        }

        onNodeWithText("Meridian").assertHasClickAction().performClick()
        assertEquals("agent-meridian", clicked)
    }

    @Test
    fun inboundAgentMessageIsAccessibleViaContentDescription() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
                        id = "inbound-5",
                        role = "user",
                        content = "Ping",
                        timestamp = "2026-08-15T12:00:00Z",
                        agentMessageProvenance = inboundProvenance(),
                    ),
                )
            }
        }

        // No resolver wired in this test, so the a11y label uses the same
        // short-id fallback the visual label shows (never a raw internal id
        // read aloud verbatim, and never blank).
        onNodeWithContentDescription(
            "Agent message, inbound, from Agent meridian to Agent pm-letta, delivered",
        ).assertExists()
    }

    @Test
    fun outboundAgentMessageSendToolCallShowsSenderToRecipientLabel() = runComposeUiTest {
        val outboundProvenance = AgentMessageProvenance(
            direction = AgentMessageDirection.OUTBOUND,
            fromAgentId = "agent-pm-letta-mobile",
            toAgentId = "agent-meridian",
            msgId = "msg-2",
            deliveryState = AgentMessageDeliveryState.RECEIVER_CONFIRMED,
        )
        setContent {
            MaterialTheme {
                DesktopMessageBubble(
                    UiMessage(
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
                                agentMessageProvenance = outboundProvenance,
                            ),
                        ),
                    ),
                )
            }
        }

        onNodeWithText("Agent message", substring = true).assertExists()
        onNodeWithTag("tool-card-toggle").assertExists()
    }
}
