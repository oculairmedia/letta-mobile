package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretRefusalText
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestList
import com.letta.mobile.data.secrets.request.SecretRequestMethods
import com.letta.mobile.data.secrets.request.SecretRequestReceiptResult
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.data.secrets.wire.SecretValueBase64
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [AdminRpcSecretRequestClient] needs from a host connection: one admin RPC and what the host
 * advertised. It mirrors [IChannelTransport.adminRpc]; see [asSecretRequestLink].
 */
interface SecretRequestLink {
    /** The capabilities the host advertised on this connection. */
    val hostCapabilities: Set<String>

    /** Sends one admin RPC with [body] as its params; throws when the call did not complete. */
    suspend fun call(method: String, body: String): AppServerInboundFrame.AdminRpcResponse
}

/** This transport as a [SecretRequestLink]. */
fun IChannelTransport.asSecretRequestLink(): SecretRequestLink = object : SecretRequestLink {
    override val hostCapabilities: Set<String> get() = this@asSecretRequestLink.hostCapabilities

    override suspend fun call(method: String, body: String): AppServerInboundFrame.AdminRpcResponse =
        this@asSecretRequestLink.adminRpc(method = method, path = "", body = body)
}

/**
 * The human side of the secret request flow over the host's admin RPC routes
 * (`secret.request.list`, `.submit`, `.cancel`; contract `secret-request-v1.md`).
 *
 * - Every call first checks that the host advertises [SecretRequestRouteWire.CAPABILITY]; without it
 *   [pending] lists nothing and [submit] / [decline] answer [SecretClientOutcome.Unreachable]
 *   without sending anything.
 * - [submit] borrows the [SecretInput] only to encode it: the bytes are lent, base64-encoded into a
 *   char array that is zeroed in `finally`, and the input itself is wiped when the call returns,
 *   throws or is cancelled. It never retries, and nothing it logs or throws carries the value.
 * - A refusal with one of the contract's codes is [SecretClientOutcome.Refused]; anything else (no
 *   answer, an unreadable answer, an internal error) is [SecretClientOutcome.Unreachable], so the
 *   person types the value again rather than the client guessing.
 *
 * What it cannot wipe: the base64 [String] the JSON layer needs, the params body [String], and the
 * transport's own copies of the request frame.
 */
class AdminRpcSecretRequestClient(
    private val link: SecretRequestLink,
    private val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
) : SecretRequestClient {
    private val supported: Boolean get() = SecretRequestRouteWire.CAPABILITY in link.hostCapabilities

    override fun pending(scope: SecretRequestScope): Flow<List<SecretRequestRecord>> = flow {
        while (currentCoroutineContext().isActive) {
            fetchPending()?.let { all -> emit(all.filter(scope::covers)) }
            delay(pollInterval)
        }
    }.distinctUntilChanged()

    override suspend fun submit(requestId: SecretRequestId, value: SecretInput): SecretClientOutcome = try {
        if (supported) send(SecretRequestMethods.SUBMIT, submitBody(requestId, value)) else SecretClientOutcome.Unreachable
    } finally {
        value.wipe()
    }

    override suspend fun decline(requestId: SecretRequestId): SecretClientOutcome {
        if (!supported) return SecretClientOutcome.Unreachable
        val body = buildJsonObject { put(SecretRequestRouteWire.REQUEST_ID, requestId.value) }.toString()
        return send(SecretRequestMethods.CANCEL, body)
    }

    /** The pending requests the host lists; empty without the capability; null when the host did not answer. */
    private suspend fun fetchPending(): List<SecretRequestRecord>? {
        if (!supported) return emptyList()
        val response = runCatchingCancellable { link.call(SecretRequestMethods.LIST, EMPTY_PARAMS) }.getOrNull()
        val listed = response?.takeIf { it.success }?.result?.let { decodeOrNull(SecretRequestList.serializer(), it) }
        return listed?.requests?.filter { it.status.state == SecretRequestState.PENDING }
    }

    private suspend fun send(method: String, body: String): SecretClientOutcome =
        runCatchingCancellable { link.call(method, body) }.fold(onSuccess = ::outcomeOf, onFailure = { SecretClientOutcome.Unreachable })

    /** Defaults. */
    companion object {
        /** How often [pending] asks the host while it is collected. */
        val DEFAULT_POLL_INTERVAL: Duration = 5.seconds

        private const val EMPTY_PARAMS = "{}"
    }
}

private val clientJson: Json = LettaJson.wire

/**
 * The submit params. The value's bytes are lent only to the encoder, and its base64 chars are
 * zeroed before this returns; the [String] the JSON layer needs cannot be.
 */
private fun submitBody(requestId: SecretRequestId, value: SecretInput): String {
    val chars = value.revealSecretBytes(SecretValueBase64::encode)
    return try {
        buildJsonObject {
            put(SecretRequestRouteWire.REQUEST_ID, requestId.value)
            put(SecretRequestRouteWire.VALUE, chars.concatToString())
        }.toString()
    } finally {
        chars.fill(ZERO_CHAR)
    }
}

private const val ZERO_CHAR = '\u0000'

private fun outcomeOf(response: AppServerInboundFrame.AdminRpcResponse): SecretClientOutcome {
    if (!response.success) {
        return SecretRefusalText.parse(response.error.orEmpty())?.let(SecretClientOutcome::Refused)
            ?: SecretClientOutcome.Unreachable
    }
    val receipt = response.result?.let { decodeOrNull(SecretRequestReceiptResult.serializer(), it) }?.receipt
    return receipt?.let(SecretClientOutcome::Settled) ?: SecretClientOutcome.Unreachable
}

private fun <T> decodeOrNull(serializer: KSerializer<T>, element: JsonElement): T? =
    runCatching { clientJson.decodeFromJsonElement(serializer, element) }.getOrNull()

private fun SecretRequestScope.covers(record: SecretRequestRecord): Boolean = when (this) {
    SecretRequestScope.Everyone -> true
    is SecretRequestScope.Agent -> record.details.requestingAgent == agent
}
