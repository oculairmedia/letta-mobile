package com.letta.mobile.data.controller.node.iroh.beads

import com.letta.mobile.data.beads.BeadsHttpException
import com.letta.mobile.data.beads.BeadsOperation
import com.letta.mobile.data.beads.BeadsProtocolException
import com.letta.mobile.data.beads.BeadsTransportException
import com.letta.mobile.data.beads.BeadsUnsupportedOperation
import com.letta.mobile.data.beads.KtorBeadsBackend
import com.letta.mobile.data.controller.node.iroh.errors.AdminRpcClientError
import io.ktor.client.engine.cio.CIO
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The explicitly selected unary bd operations. Streaming is a separate protocol change. */
object BeadsRoutes {
    val reads: Map<String, BeadsOperation> = mapOf(
        "beads.ready" to BeadsOperation.LIST_READY_WORK,
        "beads.ready.count" to BeadsOperation.COUNT_READY_WORK,
        "beads.issues.get" to BeadsOperation.GET_ISSUE,
        "beads.issues.list" to BeadsOperation.LIST_ISSUES,
        "beads.dependencies.tree" to BeadsOperation.GET_DEPENDENCY_TREE,
        "beads.dependencies.cycles" to BeadsOperation.LIST_DEPENDENCY_CYCLES,
        "beads.events.list" to BeadsOperation.LIST_EVENTS,
        "beads.stats" to BeadsOperation.GET_STATS,
        "beads.context" to BeadsOperation.GET_CONTEXT,
        "beads.config.get" to BeadsOperation.GET_SETTING,
        "beads.memories.list" to BeadsOperation.LIST_MEMORIES,
    )
    val writes: Map<String, BeadsOperation> = mapOf(
        "beads.issues.create" to BeadsOperation.CREATE_ISSUE,
        "beads.issues.update" to BeadsOperation.UPDATE_ISSUE,
        "beads.issues.close" to BeadsOperation.CLOSE_ISSUE,
        "beads.issues.claim" to BeadsOperation.CLAIM_ISSUE,
        "beads.issues.release" to BeadsOperation.RELEASE_ISSUE,
        "beads.issues.claimNext" to BeadsOperation.CLAIM_NEXT_ISSUE,
        "beads.dependencies.add" to BeadsOperation.ADD_DEPENDENCIES,
        "beads.dependencies.remove" to BeadsOperation.REMOVE_DEPENDENCY,
        "beads.config.set" to BeadsOperation.SET_SETTING,
        "beads.memories.write" to BeadsOperation.REMEMBER_MEMORY,
    )
}

/** Owns the upstream credential and lazily handshakes before dispatching any operation. */
class BeadsRouteClient(private val baseUrl: String, private val tokenFile: String?) {
    private val mutex = Mutex()
    private var backend: KtorBeadsBackend? = null
    private var token: String? = null

    suspend fun execute(operation: BeadsOperation, params: JsonObject?): JsonElement {
        val query = readQuery(params)
        val path = readPath(operation, params)
        val body = params?.get("body")
        val result = request(operation, path, query, body)
        if (token?.let { containsSecret(result, it) } == true) refuse("beads.invalid_response")
        return result
    }

    private suspend fun request(
        operation: BeadsOperation,
        path: Map<String, String>,
        query: Map<String, List<String>>,
        body: JsonElement?,
    ): JsonElement = try {
        connect().execute(operation, path, query, body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: BeadsUnsupportedOperation) {
        refuse("capability_unavailable: beads operation unsupported")
    } catch (failure: BeadsHttpException) {
        refuse(httpError(failure.status))
    } catch (_: BeadsProtocolException) {
        refuse("beads.invalid_response")
    } catch (_: BeadsTransportException) {
        refuse("beads.unavailable")
    } catch (_: Exception) {
        refuse("beads.unavailable")
    }

    private fun httpError(status: Int): String = when (status) {
        401, 403 -> "beads.unauthorized"
        else -> "beads.upstream_error"
    }

    private suspend fun connect(): KtorBeadsBackend = mutex.withLock {
        backend ?: run {
            val secret = tokenFile?.let { File(it).readLines().firstOrNull { line -> line.isNotBlank() }?.trim() }
            if (tokenFile != null && secret.isNullOrEmpty()) error("Missing Beads token")
            val engine = CIO.create()
            var connected = false
            try {
                KtorBeadsBackend.connect(engine, baseUrl, secret).also {
                    token = secret
                    backend = it
                    connected = true
                }
            } finally {
                if (!connected) engine.close()
            }
        }
    }

    private fun readQuery(params: JsonObject?): Map<String, List<String>> {
        val query = params?.get("query") ?: return emptyMap()
        if (query !is JsonObject) refuse("validation.invalid_params: query")
        return query.mapValues { (_, value) ->
            if (value !is JsonArray) refuse("validation.invalid_params: query")
            value.map { item ->
                if (item !is JsonPrimitive || !item.isString) refuse("validation.invalid_params: query")
                item.content
            }
        }
    }

    private fun readPath(operation: BeadsOperation, params: JsonObject?): Map<String, String> {
        val key = when (operation) {
            BeadsOperation.GET_ISSUE, BeadsOperation.UPDATE_ISSUE, BeadsOperation.CLOSE_ISSUE,
            BeadsOperation.CLAIM_ISSUE, BeadsOperation.RELEASE_ISSUE -> "id"
            BeadsOperation.GET_SETTING, BeadsOperation.SET_SETTING -> "key"
            else -> return emptyMap()
        }
        val value = (params?.get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
            ?: refuse("validation.invalid_params: $key")
        return mapOf(key to value.content)
    }

    private fun containsSecret(value: JsonElement, secret: String): Boolean = when (value) {
        is JsonObject -> value.any { (key, child) -> secret in key || containsSecret(child, secret) }
        is JsonArray -> value.any { containsSecret(it, secret) }
        is JsonPrimitive -> secret in value.content
    }
}

/** Answers the caller with a fixed beads code: the admin router passes only this type's text through. */
private fun refuse(code: String): Nothing = throw AdminRpcClientError(code)
