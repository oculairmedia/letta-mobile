@file:Suppress("MaxLineLength")

package com.letta.mobile.bot.tools

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Risk taxonomy for Android-hosted tools before they mutate or disclose local device state. */
enum class HostToolRiskLevel {
    Low,
    Medium,
    High,
    VeryHigh,
    Root,
}

/** Approval policy selected by tool/capability metadata. */
enum class HostToolApprovalPolicy {
    None,
    AskEveryTime,
    RememberPerSession,
    RememberPerScope,
    Forbidden,
}

/** Transport the app should use when a tool cannot be executed until the user decides. */
enum class HostToolApprovalTransport {
    InApp,
    Notification,
    Deferred,
}

enum class HostToolExecutionDisposition {
    Allowed,
    ApprovalRequired,
    Denied,
    Unavailable,
    Failed,
}

data class HostToolApprovalMetadata(
    val policy: HostToolApprovalPolicy = HostToolApprovalPolicy.None,
    val riskLevel: HostToolRiskLevel = HostToolRiskLevel.Low,
    val capabilityId: String? = null,
    val operation: String? = null,
    val scopeArgumentNames: Set<String> = emptySet(),
    val redactedArgumentNames: Set<String> = emptySet(),
    val auditEnabled: Boolean = false,
) {
    companion object {
        val None = HostToolApprovalMetadata()
    }
}

data class HostToolApprovalRequest(
    val toolName: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val capabilityId: String? = null,
    val operation: String? = null,
    val riskLevel: HostToolRiskLevel = HostToolRiskLevel.Low,
    val policy: HostToolApprovalPolicy = HostToolApprovalPolicy.None,
    val scopeKey: String? = null,
    val redactedArgumentNames: Set<String> = emptySet(),
    val auditEnabled: Boolean = false,
    val requestId: String = UUID.randomUUID().toString(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

sealed interface HostToolApprovalDecision {
    val disposition: HostToolExecutionDisposition
    val reason: String

    data class Allowed(
        override val reason: String = "Allowed by host-tool approval policy.",
        val remembered: Boolean = false,
    ) : HostToolApprovalDecision {
        override val disposition: HostToolExecutionDisposition = HostToolExecutionDisposition.Allowed
    }

    data class RequiresApproval(
        val approvalId: String = UUID.randomUUID().toString(),
        val transport: HostToolApprovalTransport = HostToolApprovalTransport.InApp,
        override val reason: String = "User approval is required before this host tool can run.",
    ) : HostToolApprovalDecision {
        override val disposition: HostToolExecutionDisposition = HostToolExecutionDisposition.ApprovalRequired
    }

    data class Denied(
        override val reason: String,
    ) : HostToolApprovalDecision {
        override val disposition: HostToolExecutionDisposition = HostToolExecutionDisposition.Denied
    }

    data class Unavailable(
        override val reason: String,
    ) : HostToolApprovalDecision {
        override val disposition: HostToolExecutionDisposition = HostToolExecutionDisposition.Unavailable
    }
}

data class HostToolRememberedApproval(
    val toolName: String,
    val policy: HostToolApprovalPolicy,
    val scopeKey: String? = null,
)

data class HostToolAuditEvent(
    val toolName: String,
    val disposition: HostToolExecutionDisposition,
    val riskLevel: HostToolRiskLevel,
    val capabilityId: String? = null,
    val operation: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val result: JsonObject = JsonObject(emptyMap()),
    val redactedFieldNames: Set<String> = emptySet(),
    val approvalId: String? = null,
    val eventId: String = UUID.randomUUID().toString(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

interface HostToolApprovalEngine {
    suspend fun evaluate(request: HostToolApprovalRequest): HostToolApprovalDecision
    suspend fun recordAudit(event: HostToolAuditEvent)
    suspend fun remember(approval: HostToolRememberedApproval)
}

@Singleton
class DefaultHostToolApprovalEngine @Inject constructor() : HostToolApprovalEngine {
    private val mutex = Mutex()
    private val rememberedSessionTools = mutableSetOf<String>()
    private val rememberedScopes = mutableSetOf<String>()
    private val auditEvents = mutableListOf<HostToolAuditEvent>()

    override suspend fun evaluate(request: HostToolApprovalRequest): HostToolApprovalDecision = mutex.withLock {
        when (request.policy) {
            HostToolApprovalPolicy.None -> HostToolApprovalDecision.Allowed()
            HostToolApprovalPolicy.Forbidden -> HostToolApprovalDecision.Denied("Host tool is forbidden by local approval policy.")
            HostToolApprovalPolicy.AskEveryTime -> HostToolApprovalDecision.RequiresApproval()
            HostToolApprovalPolicy.RememberPerSession -> if (request.toolName in rememberedSessionTools) {
                HostToolApprovalDecision.Allowed(remembered = true)
            } else {
                HostToolApprovalDecision.RequiresApproval()
            }
            HostToolApprovalPolicy.RememberPerScope -> if (request.scopeMemoryKey() in rememberedScopes) {
                HostToolApprovalDecision.Allowed(remembered = true)
            } else {
                HostToolApprovalDecision.RequiresApproval()
            }
        }
    }

    override suspend fun recordAudit(event: HostToolAuditEvent) {
        mutex.withLock { auditEvents += HostToolAuditRedactor.redact(event) }
    }

    override suspend fun remember(approval: HostToolRememberedApproval) {
        mutex.withLock {
            when (approval.policy) {
                HostToolApprovalPolicy.RememberPerSession -> rememberedSessionTools += approval.toolName
                HostToolApprovalPolicy.RememberPerScope -> rememberedScopes += approval.scopeMemoryKey()
                HostToolApprovalPolicy.None,
                HostToolApprovalPolicy.AskEveryTime,
                HostToolApprovalPolicy.Forbidden,
                -> Unit
            }
        }
    }

    suspend fun recordedAuditEvents(): List<HostToolAuditEvent> = mutex.withLock { auditEvents.toList() }

    private fun HostToolApprovalRequest.scopeMemoryKey(): String = listOf(toolName, scopeKey.orEmpty()).joinToString("::")

    private fun HostToolRememberedApproval.scopeMemoryKey(): String = listOf(toolName, scopeKey.orEmpty()).joinToString("::")
}

object HostToolAuditRedactor {
    private const val DEFAULT_MAX_FIELD_CHARS = 512
    private const val REDACTED_VALUE = "[REDACTED]"

    fun redact(
        event: HostToolAuditEvent,
        maxFieldChars: Int = DEFAULT_MAX_FIELD_CHARS,
    ): HostToolAuditEvent = event.copy(
        arguments = event.arguments.redactJson(event.redactedFieldNames, maxFieldChars).jsonObject,
        result = event.result.redactJson(event.redactedFieldNames, maxFieldChars).jsonObject,
    )

    private fun JsonElement.redactJson(
        redactedFieldNames: Set<String>,
        maxFieldChars: Int,
        key: String? = null,
    ): JsonElement = when (this) {
        is JsonObject -> JsonObject(mapValues { (childKey, childValue) ->
            childValue.redactJson(redactedFieldNames, maxFieldChars, childKey)
        })
        is JsonArray -> JsonArray(map { element -> element.redactJson(redactedFieldNames, maxFieldChars, key) })
        is JsonPrimitive -> when {
            key in redactedFieldNames -> JsonPrimitive(REDACTED_VALUE)
            isString -> JsonPrimitive(contentOrNull.orEmpty().truncate(maxFieldChars))
            else -> this
        }
        JsonNull -> this
    }

    private fun String.truncate(maxFieldChars: Int): String =
        if (length <= maxFieldChars) this else take(maxFieldChars) + "…"
}

object HostToolStructuredResults {
    fun denied(
        request: HostToolApprovalRequest,
        decision: HostToolApprovalDecision,
    ): JsonObject = buildJsonObject {
        put("status", when (decision.disposition) {
            HostToolExecutionDisposition.ApprovalRequired -> "approval_required"
            HostToolExecutionDisposition.Denied -> "denied"
            else -> "denied"
        })
        put("tool", request.toolName)
        request.capabilityId?.let { put("capability_id", it) }
        request.operation?.let { put("operation", it) }
        put("risk_level", request.riskLevel.name)
        put("reason", decision.reason)
        put("approval", buildJsonObject {
            put("required", true)
            put("policy", request.policy.name)
            put("disposition", decision.disposition.name)
            if (decision is HostToolApprovalDecision.RequiresApproval) {
                put("approval_id", decision.approvalId)
                put("transport", decision.transport.name)
            }
        })
    }

    fun unavailable(
        request: HostToolApprovalRequest,
        reason: String,
    ): JsonObject = buildJsonObject {
        put("status", "unavailable")
        put("tool", request.toolName)
        request.capabilityId?.let { put("capability_id", it) }
        put("reason", reason)
    }
}

fun HostToolApprovalMetadata.toRequest(
    toolName: String,
    arguments: JsonObject,
): HostToolApprovalRequest = HostToolApprovalRequest(
    toolName = toolName,
    arguments = arguments,
    capabilityId = capabilityId,
    operation = operation,
    riskLevel = riskLevel,
    policy = policy,
    scopeKey = scopeArgumentNames.mapNotNull { key -> arguments[key]?.jsonPrimitive?.contentOrNull }.joinToString("|").ifBlank { null },
    redactedArgumentNames = redactedArgumentNames,
    auditEnabled = auditEnabled,
)
