package com.letta.mobile.ui.devfixtures

import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.chat.projection.CanvasArtifactError
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.collections.immutable.persistentListOf

/**
 * letta-mobile-bglj6.1: the conversation the shared chat page's phone fixtures show, one copy for
 * sharedUI's phone snapshot tests and desktop's phone playground.
 *
 * A taco-night conversation with an attached screenshot, a long markdown reply and a canvas tool
 * call; plus [canvasArtifactState], the canvas.compose receipts (published, pending, refused).
 */
object PhoneFixtures {
    /** A Pixel 9 Pro's window in dp; the snapshot tests draw it at 2x. */
    const val PHONE_WIDTH_DP: Int = 412
    const val PHONE_HEIGHT_DP: Int = 915

    /** The agent every fixture talks to; mascot shells register this id. */
    const val AGENT_ID: String = "agent-1"
    const val AGENT_NAME: String = "Meridian"
    const val CONVERSATION_ID: String = "conv-1"

    /** The phone's idiom, exactly as Android's SharedChatPage sets it. */
    val touchAppearance: ChatSurfaceAppearance =
        ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch, toolDetails = ChatToolDetails.Sheet)

    /** A small gradient PNG standing in for a screenshot the person attached. */
    val screenshot: UiImageAttachment by lazy { UiImageAttachment(base64 = sampleImageBase64(), mediaType = "image/png") }

    const val LONG_REPLY: String = "Here's a list for **six people**:\n\n" +
        "- 2 lb ground beef, or black beans for the vegetarians\n" +
        "- 18 corn tortillas and a dozen flour ones\n" +
        "- Salsa, limes, cilantro and a white onion\n" +
        "- Two avocados for guacamole, plus a jalapeño\n" +
        "- Shredded cheese, sour cream, lettuce\n\n" +
        "I put it on the board as a checklist so you can tick things off at the shop."

    /** The taco-night conversation: a prompt with an image, a reply, a prompt, a long reply with a tool call. */
    val messages by lazy {
        persistentListOf(
            UiMessage(
                id = "u0",
                role = "user",
                content = "just testing heres where we now are",
                timestamp = "2026-10-01T10:18:00Z",
                attachments = listOf(screenshot),
            ),
            UiMessage(
                id = "a0",
                role = "assistant",
                content = "I can see it. The architecture column is on the left and a blue cursive \"Hello World\" sits in the middle.",
                timestamp = "2026-10-01T10:18:09Z",
                runId = "run-0",
            ),
            UiMessage(id = "u1", role = "user", content = "Plan a shopping list for a taco night for six.", timestamp = "2026-10-01T10:19:00Z"),
            UiMessage(
                id = "a1",
                role = "assistant",
                content = LONG_REPLY,
                timestamp = "2026-10-01T10:19:09Z",
                runId = "run-1",
                toolCalls = listOf(
                    UiToolCall(name = "canvas_add_element", arguments = "{\"kind\":\"checklist\"}", result = "ok", status = "success", toolCallId = "t1"),
                ),
            ),
        )
    }

    /** The settled conversation. */
    val state: ChatUiState by lazy {
        ChatUiState(
            conversationState = ConversationState.Ready(CONVERSATION_ID),
            messages = messages,
            isLoadingMessages = false,
            agentName = AGENT_NAME,
            agentId = AGENT_ID,
        )
    }

    /** An empty draft that can send, on the model the screenshots were taken with. */
    val idleComposer: ChatComposerUiState = ChatComposerUiState(
        canSend = true,
        model = ChatModelUiState(currentHandle = "lmstudio/MiniMax-M3", currentLabel = "lmstudio/MiniMax-M3"),
    )

    /** The draft the "typing" screens show. */
    val typingComposer: ChatComposerUiState = idleComposer.copy(text = "Make it vegetarian")

    /** The run is streaming: the composer shows Stop. */
    val streamingState: ChatUiState by lazy { state.copy(isStreaming = true, isAgentTyping = true) }

    /** An empty board conversation (the canvas's first look). */
    val emptyState: ChatUiState by lazy { state.copy(messages = persistentListOf()) }

    /** A prompt sent from the canvas while the agent is thinking. */
    val thinkingState: ChatUiState by lazy { state.copy(messages = persistentListOf(messages[2]), isAgentTyping = true) }

    /** A prompt and a short reply: the head's reply popup. */
    val shortReplyState: ChatUiState by lazy {
        state.copy(messages = persistentListOf(messages[2], messages[3].copy(content = "Done: the list is on the board.")))
    }

    /** The receipts' shared fields: this conversation's board, at one revision. */
    private val receiptBase = CanvasArtifactReceipt(
        artifactId = "",
        canvasId = "canvas-conversation-$CONVERSATION_ID",
        revision = 42,
        status = CanvasArtifactStatus.Pending,
        title = null,
        kinds = emptyList(),
        itemCount = 1,
        bounds = null,
    )

    /** A published artifact on its narration, with where it landed on the board. */
    private val weekendPlanReceipt = receiptBase.copy(
        artifactId = "weekend-plan",
        status = CanvasArtifactStatus.Published,
        title = "Weekend plan",
        kinds = listOf(ComposeKind.TEXT, ComposeKind.CHECKLIST, ComposeKind.NOTE, ComposeKind.GROUP, ComposeKind.CARD),
        itemCount = 6,
        bounds = ComposeBounds(80f, 80f, 712f, 746f),
    )

    /** One still being added. */
    private val packingReceipt = receiptBase.copy(artifactId = "packing", title = "Packing list", kinds = listOf(ComposeKind.CHECKLIST))

    /** One the board refused. */
    private val budgetReceipt = receiptBase.copy(
        artifactId = "budget",
        status = CanvasArtifactStatus.Failed,
        title = "Budget",
        kinds = listOf(ComposeKind.CARD),
        error = CanvasArtifactError("VALIDATION_FAILED", "a CARD holds at most 8 fields (got 11)", problemCount = 2),
    )

    /** The canvas.compose cards (letta-mobile-bglj6.13): a published artifact, one being added, a refused one. */
    val canvasArtifactState: ChatUiState by lazy {
        val composeCall = UiToolCall(
            name = "canvas_compose", arguments = "{\"title\":\"Weekend plan\"}", result = "{\"ok\":true}", status = "success", toolCallId = "t1",
        )
        ChatUiState(
            conversationState = ConversationState.Ready(CONVERSATION_ID),
            isLoadingMessages = false,
            agentName = AGENT_NAME,
            agentId = AGENT_ID,
            messages = persistentListOf(
                UiMessage(id = "u1", role = "user", content = "Plan my weekend on the board.", timestamp = "2026-10-01T10:00:00Z"),
                UiMessage(
                    id = "tc1", role = "assistant", content = "", timestamp = "2026-10-01T10:00:04Z", runId = "run-1",
                    toolCalls = listOf(composeCall),
                ),
                UiMessage(
                    id = "a1", role = "assistant", content = "I put the plan on the board: a shopping checklist, meals, and a self-care group.",
                    timestamp = "2026-10-01T10:00:06Z", runId = "run-1",
                    artifacts = listOf(weekendPlanReceipt),
                ),
                UiMessage(id = "u2", role = "user", content = "Add a packing list and a budget.", timestamp = "2026-10-01T10:01:00Z"),
                UiMessage(
                    id = "a2", role = "assistant", content = "Adding a packing list now.", timestamp = "2026-10-01T10:01:03Z", runId = "run-2",
                    artifacts = listOf(packingReceipt),
                ),
                UiMessage(
                    id = "a3", role = "assistant", content = "The budget did not go on the board.", timestamp = "2026-10-01T10:01:05Z", runId = "run-2",
                    artifacts = listOf(budgetReceipt),
                ),
            ),
        )
    }

    /** A 320 x 200 gradient PNG with "Hello World" on it, base64. */
    fun sampleImageBase64(): String {
        val image = BufferedImage(SAMPLE_WIDTH, SAMPLE_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = GradientPaint(0f, 0f, java.awt.Color(0x1B2A3A), SAMPLE_WIDTH.toFloat(), SAMPLE_HEIGHT.toFloat(), java.awt.Color(0x3C6E9F))
        g.fillRect(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        g.color = java.awt.Color(0x8AB4F8)
        g.drawString("Hello World", SAMPLE_TEXT_X, SAMPLE_TEXT_Y)
        g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private const val SAMPLE_WIDTH = 320
    private const val SAMPLE_HEIGHT = 200
    private const val SAMPLE_TEXT_X = 120
    private const val SAMPLE_TEXT_Y = 105
}
