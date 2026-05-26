package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.session.LocalRuntimeProvider
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.flowOf

@Singleton
class LocalLettaCodeRuntimeProvider @Inject constructor(
    private val turnEngine: LettaCodeTurnEngine,
) : LocalRuntimeProvider {
    override val providerId: String = "local-lettacode"
    override val priority: Int = 100

    override fun supports(config: LettaConfig): Boolean =
        config.mode == LettaConfig.Mode.LOCAL && config.localRuntimeScheme() in LETTACODE_SCHEMES

    override fun descriptor(config: LettaConfig): BackendDescriptor {
        val backendKey = config.backendKey()
        return BackendDescriptor(
            backendId = BackendId("local-lettacode:$backendKey"),
            runtimeId = RuntimeId("local-lettacode:$backendKey"),
            kind = BackendKind.LocalLettaCode,
            label = "Local LettaCode",
            capabilities = BackendCapabilities(
                supportsStreaming = true,
                supportsMemFs = true,
                supportsTools = true,
                supportsApprovals = true,
                supportsAgentFileImport = false,
                supportsAgentFileExport = false,
            ),
        )
    }

    override fun turnEngine(config: LettaConfig): TurnEngine = turnEngine

    private companion object {
        val LETTACODE_SCHEMES = setOf("local", "local-lettacode", "local-letta-code")
    }
}

@Singleton
class LocalKoogRuntimeProvider @Inject constructor() : LocalRuntimeProvider {
    override val providerId: String = "local-koog"
    override val priority: Int = 10

    override fun supports(config: LettaConfig): Boolean =
        config.mode == LettaConfig.Mode.LOCAL && config.localRuntimeScheme() == "local-koog"

    override fun descriptor(config: LettaConfig): BackendDescriptor {
        val backendKey = config.backendKey()
        return BackendDescriptor(
            backendId = BackendId("local-koog:$backendKey"),
            runtimeId = RuntimeId("local-koog:$backendKey"),
            kind = BackendKind.LocalKoog,
            label = "Local Koog runtime",
            capabilities = BackendCapabilities(
                supportsStreaming = true,
                supportsMemFs = true,
                supportsTools = false,
                supportsApprovals = false,
                supportsAgentFileImport = false,
                supportsAgentFileExport = false,
            ),
        )
    }

    override fun turnEngine(config: LettaConfig): TurnEngine = TurnEngine { command ->
        flowOf(
            RuntimeEventDraft(
                backendId = command.backendId,
                runtimeId = command.runtimeId,
                agentId = command.agentId,
                conversationId = command.conversationId,
                source = RuntimeEventSource.LocalRuntime,
                payload = RuntimeEventPayload.RunLifecycleChanged(
                    status = RuntimeRunStatus.Failed,
                    reason = "Koog local runtime adapter is not configured yet.",
                ),
            )
        )
    }
}

private fun LettaConfig.localRuntimeScheme(): String =
    serverUrl.trim().let { trimmed ->
        trimmed.substringBefore("://", missingDelimiterValue = trimmed).lowercase()
    }

private fun LettaConfig.backendKey(): String = id.takeIf { it.isNotBlank() } ?: "device"
