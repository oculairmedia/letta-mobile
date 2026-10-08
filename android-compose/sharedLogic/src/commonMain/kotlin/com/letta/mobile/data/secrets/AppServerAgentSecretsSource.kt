package com.letta.mobile.data.secrets

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerSecretCommand
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [AgentSecretsSource] over an App Server connection (letta-mobile-bzvro.25): `secret_list` and
 * `secret_apply`. Nothing here logs; a response that cannot be decoded fails WITHOUT its cause,
 * because a JSON decoding error quotes the input, and the input holds the values.
 */
class AppServerAgentSecretsSource(
    private val client: suspend () -> AppServerClient,
    private val requestId: (String) -> String,
) : AgentSecretsSource {
    override suspend fun list(agentId: String): List<AgentSecret> =
        call(AppServerSecretCommand.SecretList(requestId("secret-list"), agentId), SecretListResponse.serializer())
            .secrets
            .map { AgentSecret(key = it.key, value = SecretValue(it.value)) }
            .sortedBy { it.key }

    override suspend fun apply(agentId: String, changes: AgentSecretChanges): List<String> {
        val command = AppServerSecretCommand.SecretApply(
            requestId = requestId("secret-apply"),
            agentId = agentId,
            set = changes.set.entries.associate { (key, value) -> AgentSecretKeys.normalize(key) to value.reveal() },
            unset = changes.unset.map(AgentSecretKeys::normalize),
        )
        return call(command, SecretApplyResponse.serializer()).names
    }

    private suspend fun <T : SecretsResponse> call(command: AppServerWorkspaceCommand, serializer: KSerializer<T>): T {
        val frame = guarded { client().workspaceRequest(command) }.single()
        val response = runCatching { AppServerProtocol.json.decodeFromJsonElement(serializer, frame) }
            .getOrElse { throw AgentSecretsException("The App Server sent an unreadable ${command.responseType}.") }
        if (!response.success) throw AgentSecretsException(response.error ?: "The App Server refused ${command.responseType}.")
        return response
    }

    private suspend fun <T> guarded(call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (timeout: AppServerRequestTimeoutException) {
        throw AgentSecretsException("The App Server did not answer in time.", timeout)
    } catch (unsupported: UnsupportedOperationException) {
        throw AgentSecretsException("This connection cannot manage secrets.", unsupported)
    } catch (error: Exception) {
        throw AgentSecretsException(error.message ?: "The App Server request failed.", error)
    }
}

private interface SecretsResponse {
    val success: Boolean
    val error: String?
}

@Serializable
private data class SecretListResponse(
    val secrets: List<SecretEntry> = emptyList(),
    override val success: Boolean = false,
    override val error: String? = null,
) : SecretsResponse

@Serializable
private class SecretEntry(val key: String, val value: String = "")

@Serializable
private data class SecretApplyResponse(
    val names: List<String> = emptyList(),
    override val success: Boolean = false,
    override val error: String? = null,
) : SecretsResponse

/**
 * Blanks secret values out of a `secret_list_response` or `secret_apply` frame, for anything that
 * records or exports wire frames (a frame inspector, a diagnostics bundle). Other frames pass
 * through untouched.
 */
object AgentSecretsRedaction {
    private const val SECRET_LIST_RESPONSE = "secret_list_response"
    private const val SECRET_APPLY = "secret_apply"
    private val Redacted = JsonPrimitive(AppServerProtocol.REDACTED_PLACEHOLDER)

    fun redact(frame: JsonObject): JsonObject = when ((frame["type"] as? JsonPrimitive)?.content) {
        SECRET_LIST_RESPONSE -> frame.replace("secrets") { secrets ->
            JsonArray((secrets as? JsonArray).orEmpty().map { entry -> (entry as? JsonObject)?.replace("value") { Redacted } ?: Redacted })
        }
        SECRET_APPLY -> frame.replace("set") { set -> JsonObject((set as? JsonObject).orEmpty().mapValues { Redacted }) }
        else -> frame
    }

    private fun JsonObject.replace(key: String, transform: (JsonElement) -> JsonElement): JsonObject {
        val current = this[key] ?: return this
        return JsonObject(this + (key to transform(current)))
    }
}
