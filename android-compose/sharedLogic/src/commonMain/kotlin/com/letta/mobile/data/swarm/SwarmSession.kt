@file:OptIn(ExperimentalUuidApi::class)

package com.letta.mobile.data.swarm

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.runtime.AppServerTurnStackSpec
import com.letta.mobile.data.runtime.attachTurnStack
import com.letta.mobile.data.transport.appserver.AppServerAdminMethod
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.TurnEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One authenticated connection to the host, as the swarm messenger needs it:
 * the shared App Server turn engine and `admin_rpc` reads. Built over the shared client stack
 * ([AppServerSwarmSession]); fakes implement it directly.
 */
interface SwarmSession {
    val turnEngine: TurnEngine

    /** False once the underlying connection is gone (a dropped link, not a finished turn). */
    val connected: Flow<Boolean>

    suspend fun admin(method: AppServerAdminMethod, params: JsonObject): AdminOutcome
}

/** An `admin_rpc` answer: its result, or the host's error text. */
sealed interface AdminOutcome {
    data class Ok(val result: JsonElement?) : AdminOutcome

    data class Failed(val error: String) : AdminOutcome
}

/**
 * The transport seam: dial and authenticate as this paired device, run [withSession]'s block on the
 * live session, close it when the block returns. Each call is one dial; a reconnect is another call.
 */
interface SwarmLink {
    suspend fun <T> withSession(block: suspend (SwarmSession) -> T): LinkResult<T>
}

sealed interface LinkResult<out T> {
    data class Ok<T>(val value: T) : LinkResult<T>

    data class Failed(val failure: LinkFailure) : LinkResult<Nothing>
}

sealed interface LinkFailure {
    /** No pairing on this machine: nothing to dial. */
    data object NotPaired : LinkFailure

    /** The host refused this device's identity. Fail-closed: never retried. */
    data class Denied(val reason: String) : LinkFailure

    /** Dial or handshake failed; worth retrying. */
    data class Unreachable(val reason: String) : LinkFailure
}

/**
 * The shared client -> router -> engine stack (the same `attachTurnStack` desktop, web and Android
 * use) for a swarm client: runtime_start asks for [AppServerPermissionMode.Standard] and the engine
 * never answers an approval itself ([AppServerTurnStackSpec.autoAllowApprovals] false), so every
 * tool approval waits for the person.
 */
class AppServerSwarmSession(
    private val client: AppServerClient,
    scope: CoroutineScope,
    clientInfo: AppServerRuntimeStartClientInfo = AppServerRuntimeStartClientInfo(name = SWARM_CLIENT_NAME, version = "1"),
) : SwarmSession {
    private val router = AppServerRuntimeEventRouter()

    override val turnEngine: TurnEngine = router.attachTurnStack(
        client,
        scope,
        AppServerTurnStackSpec(
            clientInfo = clientInfo,
            permissionMode = AppServerPermissionMode.Standard,
            requestIdFactory = { "swarm-turn-${Uuid.random()}" },
            autoAllowApprovals = false,
        ),
    )

    override val connected: Flow<Boolean> get() = client.isConnected

    override suspend fun admin(method: AppServerAdminMethod, params: JsonObject): AdminOutcome {
        val response = client.adminRpc(
            AppServerCommand.AdminRpc(requestId = "swarm-admin-${Uuid.random()}", method = method.value, params = params),
        )
        return if (response.success) AdminOutcome.Ok(response.result) else AdminOutcome.Failed(response.error ?: "${method.value} failed")
    }

    /** Ends the router's turns; the caller closes the transport. */
    fun close() {
        router.detach()
    }

    companion object {
        const val SWARM_CLIENT_NAME: String = "meridian-cli"
    }
}
