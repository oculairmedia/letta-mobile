package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * lgns8.9: controller-owned constant catalogs for admin domains whose
 * admin-shim implementation was itself a CONSTANT, not a datastore read.
 *
 * Evidence (admin-shim `server.ts`, `@letta-ai/letta-code` 0.29.12 host):
 *  - `GET /v1/tools` / `/v1/tools/{id}` -> `BUILTIN_TOOL_DEFINITIONS.map(vanillaTool)`,
 *    a hard-coded 14-entry list of client-side letta-code tools. No store read.
 *  - `GET /v1/providers` -> a single `vanillaProvider` built from `LMSTUDIO_BASE_URL`.
 *  - `GET /v1/models/embedding` -> a single hard-coded embedding descriptor.
 *  - `GET /v1/folders|/v1/groups|/v1/identities|/v1/jobs|/v1/archives|/v1/mcp-servers`
 *    -> `stubList` — literally `json(res, 200, [])`. The letta-code local backend
 *    has no such entity, and vanilla Letta returns a list here, so an empty list
 *    is the correct success shape (a 404/denial would regress the admin screens
 *    for no gain).
 *
 * Serving these from the controller is therefore exact parity with the shim AND
 * removes the last read-only reason for the wrapper to dial :8291. These are
 * `controller_native` in the ownership matrix: no Letta storage is touched.
 *
 * EMPTY-BY-CONTRACT is not the same as capability-unavailable: the domain is
 * answered successfully with the shape the client decodes, because the backing
 * concept genuinely has zero members here.
 */
internal object NativeAdminCatalogs {

    /** The empty success payload for a domain the local backend does not model. */
    val EmptyList: JsonArray = JsonArray(emptyList())

    /** Port of admin-shim `BUILTIN_TOOL_DEFINITIONS` (name -> description). */
    private val BUILTIN_TOOLS: List<Pair<String, String>> = listOf(
        "Bash" to "Execute a bash command on the client machine.",
        "Read" to "Read a file from the local filesystem.",
        "Write" to "Create or overwrite a file on the local filesystem.",
        "Edit" to "Apply a precise edit to a file on the local filesystem.",
        "Glob" to "Search the filesystem for files matching a glob pattern.",
        "Grep" to "Search file contents for a pattern.",
        "Skill" to "Invoke a skill from the local skill registry.",
        "Agent" to "Delegate work to a specialized subagent.",
        "TodoWrite" to "Maintain a todo list for the current session.",
        "memory" to "Manage agent memory blocks and memfs entries.",
        "TaskOutput" to "Read output from a previously dispatched task.",
        "TaskStop" to "Stop a running task.",
        "EnterPlanMode" to "Enter plan mode (proposes a plan without executing).",
        "ExitPlanMode" to "Exit plan mode.",
    )

    /** Port of `BUILTIN_TOOL_DEFINITIONS.map(vanillaTool)`. */
    fun toolCatalog(): JsonArray = buildJsonArray {
        BUILTIN_TOOLS.forEach { (name, description) -> add(vanillaTool(name, description)) }
    }

    /** Port of `handleToolDetail` — id match over the same catalog, null when absent. */
    fun tool(toolId: String): JsonObject? =
        BUILTIN_TOOLS.firstOrNull { (name, _) -> vanillaToolId(name) == toolId }
            ?.let { (name, description) -> vanillaTool(name, description) }

    /** Port of `vanillaTool`: deterministic id so successive calls return the same tool id. */
    private fun vanillaTool(name: String, description: String): JsonObject = buildJsonObject {
        put("id", vanillaToolId(name))
        put("tool_type", "custom")
        put("description", description)
        put("source_type", "python")
        put("name", name)
        put("tags", EmptyList)
        put(
            "source_code",
            "def $name():\n    \"\"\"$description\"\"\"\n    raise Exception(\"This tool executes client-side only\")",
        )
        put(
            "json_schema",
            buildJsonObject {
                put("name", name)
                put("description", description)
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", "object")
                        put("properties", JsonObject(emptyMap()))
                        put("required", EmptyList)
                    },
                )
            },
        )
        put("args_json_schema", JsonObject(emptyMap()))
        put("return_char_limit", 50_000)
        put("pip_requirements", JsonNull)
        put("npm_requirements", JsonNull)
        put("default_requires_approval", false)
        put("enable_parallel_execution", false)
        put("created_by_id", CANNED_USER_ID)
        put("last_updated_by_id", CANNED_USER_ID)
        put("project_id", JsonNull)
        put("metadata_", JsonNull)
    }

    private fun vanillaToolId(name: String): String = "tool-" + base64UrlIdHash("tool:$name")

    /** Port of `handleProviders`: one `lmstudio-local` BYOK provider off `LMSTUDIO_BASE_URL`. */
    fun providerCatalog(lmstudioBaseUrl: String): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", "provider-" + base64UrlIdHash("provider:$LMSTUDIO_PROVIDER_NAME"))
                put("name", LMSTUDIO_PROVIDER_NAME)
                put("provider_type", "openai")
                put("provider_category", "byok")
                put("api_key", JsonNull)
                put("base_url", lmstudioBaseUrl)
                put("access_key", JsonNull)
                put("region", JsonNull)
                put("api_version", JsonNull)
                put("organization_id", CANNED_ORG_ID)
                // admin-shim stamps `new Date().toISOString()` here; a constant
                // catalog has no real sync time, and mobile does not render these
                // two fields, so the controller emits null rather than inventing
                // a moving timestamp that would defeat response caching.
                put("updated_at", JsonNull)
                put("last_synced", JsonNull)
                put("api_key_enc", "placeholder")
                put("access_key_enc", JsonNull)
            },
        )
    }

    /** Port of the hard-coded `GET /v1/models/embedding` body. */
    fun embeddingModelCatalog(): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("handle", "openai/text-embedding-3-small")
                put("name", "text-embedding-3-small")
                put("display_name", "text-embedding-3-small")
                put("provider_type", "openai")
                put("provider_name", "openai")
                put("model_type", "embedding")
                put("embedding_model", "text-embedding-3-small")
                put("embedding_endpoint_type", "openai")
                put("embedding_endpoint", "https://api.openai.com/v1")
                put("embedding_dim", 1536)
                put("embedding_chunk_size", 300)
            },
        )
    }

    /** Port of admin-shim's `Buffer.from(x).toString("base64url").slice(0, 24).toLowerCase()`. */
    private fun base64UrlIdHash(input: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(input.toByteArray(Charsets.UTF_8))
            .take(ID_HASH_CHARS)
            .lowercase()

    private const val ID_HASH_CHARS = 24
    private const val LMSTUDIO_PROVIDER_NAME = "lmstudio-local"
    private const val CANNED_USER_ID = "user-00000000-0000-4000-8000-000000000000"
    private const val CANNED_ORG_ID = "org-00000000-0000-4000-8000-000000000000"

    /** Registers a set of methods that answer with a constant empty list. */
    fun registerEmptyByContract(router: AdminRpcRouter, methods: Set<String>) {
        methods.forEach { method -> router.register(method) { EmptyList } }
    }
}
