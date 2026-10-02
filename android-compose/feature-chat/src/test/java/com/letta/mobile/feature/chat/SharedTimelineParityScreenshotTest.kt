package com.letta.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.letta.mobile.data.chat.projection.ChatDisplayMode
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.ChatMessageList
import com.letta.mobile.feature.chat.screen.activeRunActivity
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineSnapshot
import com.letta.mobile.ui.components.ThinkingTextToken
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * letta-mobile-bglj6.1: the shared chat timeline (sharedUI ChatTimeline, the one row look used by
 * Android and desktop) beside the legacy Android timeline (ChatMessageList + the composer's
 * thinking token), rendered from the SAME fixture messages under the same theme.
 *
 * The legacy timeline is the visual spec. Each PNG holds both, legacy on the left and shared on
 * the right, so a reviewer (and the agent doing the port) can compare them at a glance. Written
 * to build/outputs/roborazzi/shared-timeline-parity; no baseline is recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w860dp-h960dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
class SharedTimelineParityScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeRunDark() = capture("active-run-dark", AppTheme.DARK, activeState())

    @Test
    fun activeRunLight() = capture("active-run-light", AppTheme.LIGHT, activeState())

    @Test
    fun settledRunDark() = capture("settled-run-dark", AppTheme.DARK, settledState())

    @Test
    fun settledRunLight() = capture("settled-run-light", AppTheme.LIGHT, settledState())

    @Test
    fun expandedReasoningDark() = capture(
        "expanded-reasoning-dark",
        AppTheme.DARK,
        settledState().copy(expandedReasoningMessageIds = persistentSetOf("r1")),
    )

    private fun capture(name: String, theme: AppTheme, state: ChatUiState) {
        composeRule.setContent {
            LettaTheme(appTheme = theme, themePreset = ThemePreset.DEFAULT, dynamicColor = false) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
                ) {
                    Pane("legacy Android", Modifier.weight(1f)) { LegacyTimeline(state) }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
                    Pane("shared", Modifier.weight(1f)) {
                        // Android's appearance: a tool summary opens a sheet.
                        ChatTimelineSnapshot(
                            state = state,
                            actions = NoOpChatActions,
                            appearance = ChatSurfaceAppearance(toolDetails = ChatToolDetails.Sheet),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val out = File("build/outputs/roborazzi/shared-timeline-parity").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PngQuality, it) }
        assertTrue(out.length() > 0)
    }

    @Composable
    private fun Pane(label: String, modifier: Modifier, content: @Composable () -> Unit) {
        Column(modifier.fillMaxHeight()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(LettaDimens.Space.xs),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }

    /** ChatScreen's message area: the list over the composer's thinking token. */
    @Composable
    private fun LegacyTimeline(state: ChatUiState) {
        val cache = remember { IncrementalChatRenderItemsCache() }
        val items = remember(state) {
            cache.renderItems(state.messages, ChatDisplayMode.Interactive, state.messageListChange, state.agentId)
        }
        LettaChatTheme {
            Column(Modifier.fillMaxSize()) {
                ChatMessageList(
                    state = state,
                    renderItems = items,
                    chatMode = "interactive",
                    scrollToMessageId = null,
                    activeFontScale = 1f,
                    onActiveFontScaleChange = {},
                    onFontScaleChange = {},
                    onLoadOlderMessages = {},
                    onSendMessage = {},
                    onRerunMessage = {},
                    onSubmitApproval = { _, _, _, _ -> },
                    onToggleRunCollapsed = {},
                    onToggleReasoningExpanded = {},
                    onAttachmentImageTap = null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                val thinking = state.isStreaming || state.isAgentTyping
                if (thinking) {
                    ThinkingTextToken(
                        visible = true,
                        textOverride = activeRunActivity(state.messages).label(0L),
                        reducedMotion = true,
                        contentPadding = PaddingValues(
                            start = LettaDimens.Space.lg,
                            end = LettaDimens.Space.lg,
                            top = LettaDimens.Space.xs,
                            bottom = LettaDimens.Space.xs,
                        ),
                    )
                }
                Box(Modifier.height(LettaDimens.Space.lg))
            }
        }
    }

    private fun settledState(): ChatUiState = baseState(SettledMessages)

    private fun activeState(): ChatUiState = baseState(SettledMessages + ActiveRunMessages).copy(
        isStreaming = true,
        isAgentTyping = true,
    )

    private fun baseState(messages: List<UiMessage>) = ChatUiState(
        conversationState = ConversationState.Ready("conv-parity"),
        messages = persistentListOf<UiMessage>().addAll(messages),
        isLoadingMessages = false,
        agentName = "Meridian",
        agentId = "agent-parity",
    )

    private companion object {
        const val PngQuality = 100
        const val ToolDurationMs = 1_200L

        val SettledMessages: List<UiMessage> = listOf(
            UiMessage(
                id = "u0",
                role = "user",
                content = "Morning! What's on the board today?",
                timestamp = "2026-09-29T08:00:00Z",
            ),
            UiMessage(
                id = "a0",
                role = "assistant",
                content = "Two open beads: the chat parity epic and the dock polish.",
                timestamp = "2026-09-29T08:00:05Z",
            ),
            UiMessage(
                id = "u1",
                role = "user",
                content = "Can you run the chat tests and fix whatever is failing?",
                timestamp = "2026-09-30T16:02:00Z",
            ),
            UiMessage(
                id = "r1",
                role = "assistant",
                content = "The user wants the chat tests run. I'll run the unit tests first, then read the failure.\nAfter that I'll patch the assertion.",
                timestamp = "2026-09-30T16:02:01Z",
                runId = "run-1",
                stepId = "s1",
                isReasoning = true,
                latencyMs = 2_400,
            ),
            UiMessage(
                id = "t1",
                role = "assistant",
                content = "",
                timestamp = "2026-09-30T16:02:04Z",
                runId = "run-1",
                stepId = "s2",
                toolCalls = listOf(
                    UiToolCall(
                        name = "Bash",
                        arguments = """{"command":"./gradlew :feature-chat:test"}""",
                        result = "FAILED: ChatRowTest > clock",
                        status = "error",
                        toolCallId = "tc1",
                        executionTimeMs = ToolDurationMs,
                    ),
                ),
            ),
            UiMessage(
                id = "t2",
                role = "assistant",
                content = "",
                timestamp = "2026-09-30T16:02:09Z",
                runId = "run-1",
                stepId = "s3",
                toolCalls = listOf(
                    UiToolCall(
                        name = "Bash",
                        arguments = """{"command":"git status --short"}""",
                        result = " M ChatRowTest.kt",
                        status = "success",
                        toolCallId = "tc2",
                        executionTimeMs = ToolDurationMs,
                    ),
                ),
            ),
            UiMessage(
                id = "a1",
                role = "assistant",
                content = "The clock assertion in `ChatRowTest` expected a 24-hour time. I fixed it and the suite passes now.",
                timestamp = "2026-09-30T16:02:15Z",
                runId = "run-1",
                stepId = "s4",
            ),
        )

        val ActiveRunMessages: List<UiMessage> = listOf(
            UiMessage(
                id = "u2",
                role = "user",
                content = "Great. Now update the changelog.",
                timestamp = "2026-09-30T16:04:00Z",
            ),
            UiMessage(
                id = "r2",
                role = "assistant",
                content = "Reading the changelog format before adding the entry.",
                timestamp = "",
                runId = "run-2",
                stepId = "s5",
                isReasoning = true,
            ),
            UiMessage(
                id = "t3",
                role = "assistant",
                content = "",
                timestamp = "",
                runId = "run-2",
                stepId = "s6",
                toolCalls = listOf(
                    UiToolCall(
                        name = "Read",
                        arguments = """{"file_path":"CHANGELOG.md"}""",
                        result = null,
                        status = null,
                        toolCallId = "tc3",
                        executionTimeMs = ToolDurationMs,
                    ),
                ),
            ),
        )
    }
}

/**
 * The shared page's intents, all ignored: the comparison only draws. A proxy rather than a
 * hand-written override per intent, so a new [ChatActions] member needs no edit here.
 */
internal val NoOpChatActions: ChatActions = Proxy.newProxyInstance(
    ChatActions::class.java.classLoader,
    arrayOf(ChatActions::class.java),
    NoOpChatActionsHandler,
) as ChatActions

/** Every [ChatActions] member returns Unit; Object's own members keep an object's identity semantics. */
private object NoOpChatActionsHandler : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        return when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "NoOpChatActions"
            else -> Unit
        }
    }
}
