package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputMessage
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalDecisionValue
import com.letta.mobile.runtime.ToolName
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResult
import com.letta.mobile.data.transport.appserver.AppServerExternalToolResultContent
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

/**
 * TurnEngine backed by one App Server client/control owner.
 *
 * This class serializes turns per engine instance and caches a started runtime
 * only for the same agent/conversation pair. Hosts that share one App Server
 * process across several UI clients still need an external fanout controller.
 */
class AppServerTurnEngine(
    private val client: AppServerClient,
    private val mapper: AppServerRuntimeEventMapper = AppServerRuntimeEventMapper(),
    private val clientInfo: AppServerRuntimeStartClientInfo = AppServerRuntimeStartClientInfo(
        name = "letta-mobile",
        version = "0.1",
    ),
    private val permissionMode: AppServerPermissionMode = AppServerPermissionMode.Standard,
    private val requestIdFactory: () -> String = ::defaultRequestId,
    private val externalToolRegistry: ExternalToolRegistry? = null,
) : TurnEngine {
    private val activeTurn = Mutex()
    private var runtime: AppServerRuntimeScope? = null

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
        if (!activeTurn.tryLock()) {
            throw IllegalStateException("An App Server turn is already active for ${command.runtimeId.value}.")
        }

        try {
            val scope = ensureRuntime(command)
            emit(command.startedDraft())
            client.input(command.toInputCommand(scope))

            coroutineScope {
                try {
                    client.events.collect { received ->
                        if (!received.matches(scope)) return@collect

                        val frame = received.frame
                        if (frame is AppServerInboundFrame.ExternalToolCallRequest && externalToolRegistry != null) {
                            launch {
                                try {
                                    val result = externalToolRegistry.invoke(frame.toolName, frame.input)
                                    val toolResult = when (result) {
                                        is ExternalToolResult.Success -> {
                                            AppServerExternalToolResult(
                                                content = listOf(
                                                    AppServerExternalToolResultContent(
                                                        type = "text",
                                                        text = result.content
                                                    )
                                                ),
                                                isError = false
                                            )
                                        }
                                        is ExternalToolResult.Error -> {
                                            AppServerExternalToolResult(
                                                content = listOf(
                                                    AppServerExternalToolResultContent(
                                                        type = "text",
                                                        text = result.error
                                                    )
                                                ),
                                                isError = true
                                            )
                                        }
                                    }
                                    client.sendExternalToolResponse(
                                        AppServerCommand.ExternalToolCallResponse(
                                            requestId = frame.requestId,
                                            result = toolResult,
                                        )
                                    )
                                } catch (e: Exception) {
                                    client.sendExternalToolResponse(
                                        AppServerCommand.ExternalToolCallResponse(
                                            requestId = frame.requestId,
                                            error = e.message ?: "Failed to execute external tool"
                                        )
                                    )
                                }
                            }
                        }

                        val drafts = mapper.map(command, received)
                        drafts.forEach { draft ->
                            emit(draft)
                            if (draft.isTerminalLifecycle()) {
                                throw TurnCompleted
                            }
                        }
                    }
                } catch (completed: TurnCompletedMarker) {
                    // Flow completed after a terminal App Server lifecycle event.
                }
            }
        } finally {
            activeTurn.unlock()
        }
    }

    private suspend fun ensureRuntime(command: TurnCommand): AppServerRuntimeScope {
        runtime?.let { cached ->
            if (cached.matches(command)) return cached
        }
        val response = client.runtimeStart(
            AppServerCommand.RuntimeStart(
                requestId = requestIdFactory(),
                agentId = command.agentId.value,
                conversationId = command.conversationId.value,
                mode = permissionMode,
                clientInfo = clientInfo,
                recoverApprovals = true,
                forceDeviceStatus = true,
            ),
        )
        if (!response.success) {
            error(response.error ?: "App Server runtime_start failed.")
        }
        val returnedRuntime = response.runtime ?: error("App Server runtime_start returned no runtime.")
        runtime = returnedRuntime
        return returnedRuntime
    }

    private fun AppServerRuntimeScope.matches(command: TurnCommand): Boolean =
        agentId == command.agentId.value && conversationId == command.conversationId.value

    private fun TurnCommand.toInputCommand(scope: AppServerRuntimeScope): AppServerCommand.Input =
        when (val turnInput = input) {
            is TurnInput.UserMessage -> AppServerCommand.Input(
                runtime = scope,
                payload = AppServerInputPayload.CreateMessage(
                    messages = listOf(
                        AppServerInputMessage.userText(
                            text = turnInput.text,
                            clientMessageId = turnInput.localMessageId,
                        ),
                    ),
                    clientToolAllowlist = toolPolicy.allowedTools.toWireAllowlist(),
                ),
            )
            is TurnInput.ToolApprovalResponse -> AppServerCommand.Input(
                runtime = scope,
                payload = AppServerInputPayload.ApprovalResponse(
                    requestId = turnInput.decision.approvalId.value,
                    decision = when (turnInput.decision.decision) {
                        ToolApprovalDecisionValue.Approved -> {
                            com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision.Allow(
                                message = turnInput.decision.response,
                            )
                        }
                        ToolApprovalDecisionValue.Denied,
                        ToolApprovalDecisionValue.TimedOut,
                        -> com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision.Deny(
                            message = turnInput.decision.response ?: "Denied by mobile client.",
                        )
                    },
                ),
            )
        }

    private fun Set<ToolName>.toWireAllowlist(): List<String>? =
        takeIf { it.isNotEmpty() }?.map { it.value }?.sorted()

    private fun TurnCommand.startedDraft(): RuntimeEventDraft =
        RuntimeEventDraft(
            backendId = backendId,
            runtimeId = runtimeId,
            agentId = agentId,
            conversationId = conversationId,
            source = RuntimeEventSource.LocalRuntime,
            payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started),
        )

    private fun RuntimeEventDraft.isTerminalLifecycle(): Boolean {
        val lifecycle = payload as? RuntimeEventPayload.RunLifecycleChanged ?: return false
        return lifecycle.status == RuntimeRunStatus.Completed ||
            lifecycle.status == RuntimeRunStatus.Failed ||
            lifecycle.status == RuntimeRunStatus.Cancelled
    }

    private fun com.letta.mobile.data.transport.appserver.AppServerReceivedFrame.matches(
        scope: AppServerRuntimeScope,
    ): Boolean {
        val eventRuntime = frame.runtime ?: return true
        return eventRuntime.agentId == scope.agentId &&
            eventRuntime.conversationId == scope.conversationId
    }

    private object TurnCompleted : TurnCompletedMarker()
    private sealed class TurnCompletedMarker : Throwable()

    private companion object {
        private var nextRequestId = 0

        fun defaultRequestId(): String {
            nextRequestId += 1
            return "app-server-${nextRequestId}"
        }
    }
}
