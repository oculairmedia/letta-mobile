package com.letta.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Tag("screenshot")
@OptIn(ExperimentalRoborazziApi::class)
class RunBlockScreenshotTest {
    @Test
    fun singleStepCompletedRun() {
        captureRunBlock(
            name = "runblock_single_step_completed",
            messages = listOf(
                message(
                    id = "assistant-single",
                    content = "The requested workspace scan is complete.",
                ),
            ),
        )
    }

    @Test
    fun multiStepToolRun() {
        captureRunBlock(
            name = "runblock_multi_step_tool_run",
            messages = listOf(
                message(
                    id = "reasoning",
                    content = "I should inspect the project files before answering.",
                    isReasoning = true,
                ),
                message(
                    id = "tool-success",
                    content = "",
                    toolCalls = listOf(
                        toolCall(
                            id = "tool-1",
                            name = "read_file",
                            arguments = """{"path":"android-compose/settings.gradle.kts"}""",
                            result = "include(\":feature-chat\")",
                        ),
                    ),
                ),
                message(
                    id = "assistant-final",
                    content = "The chat feature module is already included in Gradle.",
                ),
            ),
        )
    }

    @Test
    fun streamingRun() {
        captureRunBlock(
            name = "runblock_streaming_tail",
            messages = listOf(
                message(
                    id = "reasoning-stream",
                    content = "I found the relevant route and am checking the final state.",
                    isReasoning = true,
                ),
                message(
                    id = "assistant-streaming",
                    content = "The final answer is still streaming",
                    isPending = true,
                ),
            ),
            isStreaming = true,
        )
    }

    @Test
    fun toolErrorRun() {
        captureRunBlock(
            name = "runblock_tool_error",
            messages = listOf(
                message(
                    id = "reasoning-error",
                    content = "I need to run the verification command.",
                    isReasoning = true,
                ),
                message(
                    id = "tool-error",
                    content = "",
                    toolCalls = listOf(
                        toolCall(
                            id = "tool-error-1",
                            name = "gradle",
                            arguments = """{"task":":feature-chat:testDebugUnitTest"}""",
                            result = "Execution failed for task ':feature-chat:testDebugUnitTest'.",
                            status = "error",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun pendingApprovalRun() {
        val toolCallId = "tool-approval-1"
        captureRunBlock(
            name = "runblock_pending_approval",
            messages = listOf(
                message(
                    id = "reasoning-approval",
                    content = "This command needs approval before execution.",
                    isReasoning = true,
                ),
                message(
                    id = "approval",
                    content = "",
                    toolCalls = listOf(
                        toolCall(
                            id = toolCallId,
                            name = "shell",
                            arguments = """{"command":"./gradlew test"}""",
                            result = null,
                            status = null,
                        ),
                    ),
                    approvalRequest = UiApprovalRequest(
                        requestId = "approval-1",
                        toolCalls = listOf(
                            UiApprovalToolCall(
                                toolCallId = toolCallId,
                                name = "shell",
                                arguments = """{"command":"./gradlew test"}""",
                            ),
                        ),
                    ),
                ),
            ),
            activeApprovalRequestId = "approval-1",
        )
    }

    @Test
    fun nullRunIdAssistantBoundaryFallsBackToSingle() {
        val standalone = message(
            id = "assistant-null-run",
            content = "This assistant message predates run tracking.",
            runId = null,
            stepId = null,
        )
        val renderItems = groupMessagesForRender(listOf(standalone to GroupPosition.None))
        assertTrue(renderItems.single() is ChatRenderItem.Single)

        captureStandaloneMessage(
            name = "runblock_null_run_boundary_single",
            message = standalone,
        )
    }

    private fun captureRunBlock(
        name: String,
        messages: List<UiMessage>,
        isStreaming: Boolean = false,
        activeApprovalRequestId: String? = null,
    ) {
        captureContent(name) {
            RunBlock(
                messages = messages,
                collapsed = false,
                onToggleCollapsed = {},
                modifier = Modifier.fillMaxWidth(),
                isStreaming = isStreaming,
                activeApprovalRequestId = activeApprovalRequestId,
                onApprovalDecision = ::ignoreApprovalDecision,
            ) { message, position, rowModifier ->
                ChatMessageItem(
                    message = message,
                    groupPosition = position,
                    isStreaming = isStreaming && message.id == messages.lastOrNull()?.id,
                    reasoningCollapsed = false,
                    onToggleReasoning = {},
                    onApprovalDecision = ::ignoreApprovalDecision,
                    approvalInFlight = message.approvalRequest?.requestId == activeApprovalRequestId,
                    modifier = rowModifier,
                )
            }
        }
    }

    private fun captureStandaloneMessage(
        name: String,
        message: UiMessage,
    ) {
        captureContent(name) {
            ChatMessageItem(
                message = message,
                groupPosition = GroupPosition.None,
                isStreaming = false,
                reasoningCollapsed = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    private fun captureContent(
        name: String,
        content: @Composable () -> Unit,
    ) {
        captureRoboImage("src/test/snapshots/images/$name.png", ScreenshotOptions) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                LettaTheme(
                    appTheme = AppTheme.LIGHT,
                    themePreset = ThemePreset.DEFAULT,
                    dynamicColor = false,
                ) {
                    LettaChatTheme {
                        Box(
                            modifier = Modifier
                                .width(SnapshotWidth)
                                .background(MaterialTheme.colorScheme.background)
                                .padding(16.dp),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    private fun message(
        id: String,
        content: String,
        runId: String? = "run-snapshot",
        stepId: String? = id,
        isPending: Boolean = false,
        isReasoning: Boolean = false,
        toolCalls: List<UiToolCall>? = null,
        approvalRequest: UiApprovalRequest? = null,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-05-16T00:00:00Z",
        runId = runId,
        stepId = stepId,
        isPending = isPending,
        isReasoning = isReasoning,
        toolCalls = toolCalls,
        approvalRequest = approvalRequest,
    )

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
        result: String?,
        status: String? = "success",
    ) = UiToolCall(
        name = name,
        arguments = arguments,
        result = result,
        status = status,
        toolCallId = id,
    )

    @Suppress("UNUSED_PARAMETER")
    private fun ignoreApprovalDecision(
        requestId: String,
        toolCallIds: List<String>,
        approve: Boolean,
        reason: String?,
    ) = Unit

    private companion object {
        val SnapshotWidth = 360.dp
        val ScreenshotOptions = RoborazziOptions(
            captureType = RoborazziOptions.CaptureType.Screenshot(),
        )
    }
}
