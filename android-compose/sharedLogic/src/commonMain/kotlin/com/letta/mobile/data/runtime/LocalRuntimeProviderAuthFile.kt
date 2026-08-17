package com.letta.mobile.data.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Reads and merges the bundled letta-code runtime's own local-provider
 * credential file, `<localBackendStorageDir>/providers/auth.json`.
 *
 * This is grounded in the bundled runtime bundle
 * (`node_modules/@letta-ai/letta-code/letta.js`,
 * `src/backend/local/local-provider-auth-store.ts`), not guessed:
 *
 * - `getLocalProviderAuthPath()` resolves to
 *   `join(storageDir, "providers", "auth.json")`, where `storageDir`
 *   defaults to `~/.letta/lc-local-backend` but is overridden by the
 *   `LETTA_LOCAL_BACKEND_DIR` env var — which `DesktopLocalRuntimeManager`
 *   already sets when it spawns the bundled runtime.
 * - `emptyAuthFile()` returns `{ version: 1, providers: {} }`.
 * - `createOrUpdateLocalProvider()` writes each provider keyed by
 *   `providerName` as:
 *   ```json
 *   {
 *     "id": "local-provider-<providerName>",
 *     "name": "<providerName>",
 *     "provider_type": "<providerType>",
 *     "provider_category": "byok",
 *     "auth": { "type": "api", "key": "<apiKey>" },
 *     "base_url": "<baseURL>",
 *     "created_at": "<iso>",
 *     "updated_at": "<iso>"
 *   }
 *   ```
 * - The generic "OpenAI-compatible API" entry the runtime's own setup UI
 *   offers (`CLOUD_BYOK_PROVIDERS`, id `"openai-compatible"`) uses
 *   `providerType: "openai"` and `providerName: "lc-openai-compatible"`
 *   with exactly `apiKey` + `baseUrl` fields — the same generic dispatch
 *   (`createProvider3`/`createOrUpdateProvider2`) routes to this same
 *   local `auth.json` store when a local backend target is active, so
 *   reusing that provider name/type here is what the runtime's own UI
 *   would produce if a user picked "OpenAI-compatible API" from inside a
 *   terminal.
 * - Providers that don't require a key (Ollama, LM Studio, llama.cpp) use
 *   the sentinel `"not-needed"` (`LOCAL_PROVIDER_NO_API_KEY`) rather than
 *   an empty string; we do the same when no API key is supplied.
 */

/** Matches letta-code's `CLOUD_BYOK_PROVIDERS` entry id `"openai-compatible"` / `providerName: "lc-openai-compatible"`. */
const val LOCAL_RUNTIME_PROVIDER_NAME: String = "lc-openai-compatible"

/** Matches letta-code's `providerType: "openai"` for that same entry. */
internal const val LOCAL_RUNTIME_PROVIDER_TYPE: String = "openai"

private const val LOCAL_RUNTIME_PROVIDER_CATEGORY = "byok"

/** Matches letta-code's `LOCAL_PROVIDER_NO_API_KEY` sentinel. */
internal const val LOCAL_RUNTIME_NO_API_KEY_SENTINEL: String = "not-needed"

/**
 * Thrown when the existing `auth.json` contents can't be parsed as a JSON
 * object. The merge refuses to proceed rather than silently discarding
 * whatever was there — callers should leave the on-disk file untouched
 * when this is thrown.
 */
class LocalRuntimeProviderAuthFileCorruptException(message: String) : IllegalStateException(message)

/**
 * Operations on the local-provider auth storage JSON payload.
 */
object LocalRuntimeProviderAuth {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
    }

    private val managedProviderKeys = setOf(
        "id", "name", "provider_type", "provider_category", "auth", "base_url", "created_at", "updated_at",
    )

    fun merge(
        existingJson: String?,
        config: LocalRuntimeProviderConfig,
        nowIso: String,
        providerName: String = LOCAL_RUNTIME_PROVIDER_NAME,
    ): String {
        val root = parseAuthRoot(existingJson)
        val providers = (root["providers"] as? JsonObject) ?: JsonObject(emptyMap())
        val existingProvider = providers[providerName] as? JsonObject

        val createdAt = (existingProvider?.get("created_at") as? JsonPrimitive)?.contentOrNull ?: nowIso
        val id = (existingProvider?.get("id") as? JsonPrimitive)?.contentOrNull ?: "local-provider-$providerName"
        val apiKey = config.apiKey?.trim()?.takeIf { it.isNotBlank() } ?: LOCAL_RUNTIME_NO_API_KEY_SENTINEL

        val newProvider = buildJsonObject {
            existingProvider?.entries?.forEach { (key, value) ->
                if (key !in managedProviderKeys) put(key, value)
            }
            put("id", JsonPrimitive(id))
            put("name", JsonPrimitive(providerName))
            put("provider_type", JsonPrimitive(LOCAL_RUNTIME_PROVIDER_TYPE))
            put("provider_category", JsonPrimitive(LOCAL_RUNTIME_PROVIDER_CATEGORY))
            put(
                "auth",
                buildJsonObject {
                    put("type", JsonPrimitive("api"))
                    put("key", JsonPrimitive(apiKey))
                },
            )
            put("base_url", JsonPrimitive(config.baseUrl.trim()))
            put("created_at", JsonPrimitive(createdAt))
            put("updated_at", JsonPrimitive(nowIso))
        }

        val newProviders = buildJsonObject {
            providers.entries.forEach { (key, value) -> if (key != providerName) put(key, value) }
            put(providerName, newProvider)
        }

        val newRoot = buildJsonObject {
            put("version", root["version"] ?: JsonPrimitive(1))
            root.entries.forEach { (key, value) -> if (key != "providers" && key != "version") put(key, value) }
            put("providers", newProviders)
        }

        return json.encodeToString(JsonObject.serializer(), newRoot)
    }

    fun readStatus(
        existingJson: String?,
        providerName: String = LOCAL_RUNTIME_PROVIDER_NAME,
    ): LocalRuntimeProviderStatus {
        val root = runCatching { parseAuthRoot(existingJson) }.getOrNull() ?: return LocalRuntimeProviderStatus(null, false)
        val provider = (root["providers"] as? JsonObject)?.get(providerName) as? JsonObject
            ?: return LocalRuntimeProviderStatus(null, false)
        val baseUrl = (provider["base_url"] as? JsonPrimitive)?.contentOrNull
        val key = ((provider["auth"] as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull
        val hasApiKey = !key.isNullOrBlank() && key != LOCAL_RUNTIME_NO_API_KEY_SENTINEL
        return LocalRuntimeProviderStatus(baseUrl = baseUrl, hasApiKey = hasApiKey)
    }

    private fun parseAuthRoot(existingJson: String?): JsonObject {
        val trimmed = existingJson?.trim()
        if (trimmed.isNullOrEmpty()) return JsonObject(emptyMap())
        val element = try {
            json.parseToJsonElement(trimmed)
        } catch (error: Exception) {
            throw LocalRuntimeProviderAuthFileCorruptException(
                "providers/auth.json is not valid JSON: ${error.message}",
            )
        }
        return element as? JsonObject
            ?: throw LocalRuntimeProviderAuthFileCorruptException("providers/auth.json root is not a JSON object")
    }
}

/**
 * Merges [config] into [existingJson] (the current contents of
 * `providers/auth.json`, or `null`/blank if the file doesn't exist yet),
 * touching only the entry named [providerName]. Every other provider and
 * every unrecognized field — including ones this app doesn't know about —
 * round-trips verbatim.
 */
fun mergeLocalRuntimeProviderAuth(
    existingJson: String?,
    config: LocalRuntimeProviderConfig,
    nowIso: String,
    providerName: String = LOCAL_RUNTIME_PROVIDER_NAME,
): String = LocalRuntimeProviderAuth.merge(existingJson, config, nowIso, providerName)

/**
 * Reads the currently-configured base URL and whether an API key is set
 * for [providerName], without ever exposing the key value itself. Returns
 * an unconfigured status (rather than throwing) for a missing file, blank
 * content, or unparsable JSON — this is a read path for display, not a
 * write path, so it degrades gracefully.
 */
fun readLocalRuntimeProviderStatus(
    existingJson: String?,
    providerName: String = LOCAL_RUNTIME_PROVIDER_NAME,
): LocalRuntimeProviderStatus = LocalRuntimeProviderAuth.readStatus(existingJson, providerName)
