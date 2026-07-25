package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Authoritative subagent registry backed by the server-local admin shim.
 * Every request carries the parent conversation scope; no client correlation
 * state is consulted.
 *
 * Per-call resilience (audit gn7kr.3): the shim proxy round-trip can time out,
 * 5xx, or hit a network blip. Unlike [discover] — which is already fail-soft
 * via `runCatching` — the raw [list]/[todos] calls used to let any such
 * failure propagate as a thrown exception all the way to the Iroh admin_rpc
 * caller (surfacing as `success:false` instead of a graceful empty/`null`
 * result). We now:
 *   - serve a short TTL-cached result per request key so a burst of polls
 *     during a transient hiccup doesn't hammer the shim (mirrors
 *     [LocalBackendMessageReader]'s signature-keyed cache), and
 *   - trip a circuit breaker on failure so a cooldown window of subsequent
 *     calls short-circuits straight to the fail-soft result instead of
 *     re-probing a shim that just failed (mirrors [NativeAdmin]'s breaker).
 * `list` returns `emptyList()` and `todos` returns `null` under an open
 * breaker or a request failure — both are already treated as "nothing to
 * show" by [SubagentAdminHandlers], so callers degrade gracefully.
 */
class HttpSubagentRegistrySource internal constructor(
    private val proxy: AdminProxyClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Injectable so tests can drive the TTL/cooldown deterministically without sleeping. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
) : SubagentRegistrySource {

    private data class CacheEntry<T>(val value: T, val expiresAtMillis: Long)

    private val listCache = ConcurrentHashMap<String, CacheEntry<List<SubagentEntry>>>()
    private val todosCache = ConcurrentHashMap<String, CacheEntry<SubagentTodosSnapshot?>>()

    @Volatile
    private var downSinceMillis: Long? = null

    private fun circuitOpen(): Boolean {
        val down = downSinceMillis ?: return false
        return if (nowMillis() - down < cooldownMillis) {
            true
        } else {
            downSinceMillis = null
            false
        }
    }

    private fun tripBreaker() {
        downSinceMillis = nowMillis()
    }

    override suspend fun list(conversationId: String, includeTerminal: Boolean): List<SubagentEntry> {
        val key = "$conversationId|$includeTerminal"
        listCache[key]?.let { cached -> if (cached.expiresAtMillis > nowMillis()) return cached.value }
        if (circuitOpen()) return emptyList()
        return try {
            val response = withContext(Dispatchers.IO) {
                proxy.get(
                    AdminPath.shim("v1", "subagents").builder()
                        .query("conversation_id", conversationId)
                        .query("all", includeTerminal.toString())
                        .build(),
                )
            }
            val entries = json.decodeFromJsonElement(SubagentListResponse.serializer(), response).subagents
            downSinceMillis = null
            listCache[key] = CacheEntry(entries, nowMillis() + ttlMillis)
            entries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tripBreaker()
            emptyList()
        }
    }

    override suspend fun todos(conversationId: String, toolCallId: String): SubagentTodosSnapshot? {
        val key = "$conversationId|$toolCallId"
        todosCache[key]?.let { cached -> if (cached.expiresAtMillis > nowMillis()) return cached.value }
        if (circuitOpen()) return null
        return try {
            val response = withContext(Dispatchers.IO) {
                proxy.get(
                    AdminPath.shim("v1", "subagents", toolCallId, "todos").builder()
                        .query("conversation_id", conversationId)
                        .build(),
                )
            }
            val decoded = json.decodeFromJsonElement(SubagentTodosResponse.serializer(), response)
            val snapshot = if (!decoded.found || decoded.subagent == null) {
                null
            } else {
                SubagentTodosSnapshot(decoded.subagent, decoded.todos, decoded.todosFound)
            }
            downSinceMillis = null
            todosCache[key] = CacheEntry(snapshot, nowMillis() + ttlMillis)
            snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tripBreaker()
            null
        }
    }

    companion object {
        const val CAPABILITY = "subagent_registry_v1"

        /** Smooths a burst of polls during a transient hiccup without masking real updates for long. */
        private const val DEFAULT_TTL_MILLIS = 5_000L

        /** After a failure, skip the shim entirely for this long and answer fail-soft. */
        private const val DEFAULT_COOLDOWN_MILLIS = 10_000L

        /** Returns null until the shim explicitly advertises the HTTP contract. */
        suspend fun discover(adminBaseUrl: String): HttpSubagentRegistrySource? {
            val proxy = AdminProxyClient(adminBaseUrl)
            return runCatching {
                val capability = withContext(Dispatchers.IO) {
                    proxy.get(AdminPath.shim("v1", "capabilities").build())
                }.jsonObject[CAPABILITY]?.jsonObject
                val available = capability?.get("available")?.jsonPrimitive?.booleanOrNull == true
                val transport = capability?.get("transport")?.jsonPrimitive?.content
                if (available && transport == "rest") HttpSubagentRegistrySource(proxy) else null
            }.getOrNull()
        }
    }
}

@Serializable
private data class SubagentListResponse(val subagents: List<SubagentEntry> = emptyList())

@Serializable
private data class SubagentTodosResponse(
    val found: Boolean = false,
    val subagent: SubagentEntry? = null,
    val todos: List<SubagentTodo> = emptyList(),
    @kotlinx.serialization.SerialName("todos_found") val todosFound: Boolean = false,
)
