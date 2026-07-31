package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * lgns8.9: on-disk memory-block reader.
 *
 * Blocks are not a first-class entity in the letta-code local backend: each
 * block is one markdown file under `memfs/<agentId>/memory/system/<label>.md`.
 * admin-shim synthesises the globally-addressable Letta block shape from those
 * files (`store.ts:readBlocksForAgent`, `server.ts:handleBlocksList` /
 * `handleBlockDetail`), and this is a faithful port of that projection:
 *
 *  - `block.list` = union of every agent's system memory files;
 *  - `block.get`  = the first block across agents whose synthesised id matches.
 *
 * The id MUST stay `sha256("<agentId>:<label>")[..24]` — admin-shim switched off
 * a base64 slice that collided and crashed mobile's Block Library on duplicate
 * keys, so the hash is a locked wire invariant.
 *
 * READ-ONLY by construction: this reader never opens a file for writing. Block
 * mutations are NOT performed here (the epic forbids a second writer against one
 * local-backend root) — they route to the App Server's native memory-file
 * commands or fail closed. See `ToolAdminHandlers`.
 */
internal class LocalBackendBlockReader(private val support: LocalBackendStoreSupport) {

    /** Port of `GET /v1/blocks`: union of `readBlocksForAgent` over every agent. */
    fun listAllBlocks(): JsonArray? = runCatching {
        buildJsonArray {
            agentIds().forEach { agentId ->
                blocksForAgent(agentId).forEach { add(it) }
            }
        }
    }.getOrNull()

    /** Port of `GET /v1/blocks/{id}`: first agent whose synthesised block id matches. */
    fun getBlock(blockId: String): JsonObject? = runCatching {
        agentIds().firstNotNullOfOrNull { agentId ->
            blocksForAgent(agentId).firstOrNull { (it as? JsonObject)?.get("id")?.stringOrNull() == blockId }
        } as? JsonObject
    }.getOrNull()

    /** Port of `store.ts:readBlocksForAgent` — one Block per `memory/system/<label>.md` file. */
    fun blocksForAgent(agentId: String): JsonArray = buildJsonArray {
        labelsForAgent(agentId).forEach { label ->
            add(projectBlock(agentId, label, readBlockValue(agentId, label)))
        }
    }

    /** Deterministic (sorted) label list; readdir order is filesystem-dependent. */
    private fun labelsForAgent(agentId: String): List<String> {
        val files = systemMemoryDir(agentId).listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?: return emptyList()
        return files.map { it.name.removeSuffix(".md") }.sorted()
    }

    private fun readBlockValue(agentId: String, label: String): String =
        runCatching { File(systemMemoryDir(agentId), "$label.md").readText() }.getOrDefault("")

    private fun systemMemoryDir(agentId: String): File =
        File(File(File(File(support.baseDir, "memfs"), agentId), "memory"), "system")

    /** Agent ids in the same order the agent list serves them (mtime desc). */
    private fun agentIds(): List<String> {
        val dir = File(support.baseDir, "agents")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching { support.json.parseToJsonElement(f.readText()).jsonObject }
                    .getOrNull()
                    ?.get("id")?.jsonPrimitive?.contentOrNullSafe()
            }
    }

    companion object {
        /** admin-shim's synthesised block value limit. */
        const val BLOCK_VALUE_LIMIT: Int = 5000
        private const val BLOCK_ID_HASH_CHARS = 24

        /**
         * The wire Block admin-shim synthesises for one memory file. Exposed so a
         * native write can echo the post-write block without a second disk read.
         */
        fun projectBlock(agentId: String, label: String, value: String): JsonObject = buildJsonObject {
            put("id", blockIdFor(agentId, label))
            put("label", label)
            put("value", value)
            put("description", JsonNull)
            put("metadata", JsonNull)
            put("limit", BLOCK_VALUE_LIMIT)
            put("created_by_id", JsonNull)
            put("last_updated_by_id", JsonNull)
            put("is_template", false)
            put("template_name", JsonNull)
            put("preserve_on_migration", false)
            put("read_only", false)
            put("tags", JsonArray(emptyList()))
            put("hidden", JsonNull)
            put("project_id", JsonNull)
            put("template_id", JsonNull)
            put("base_template_id", JsonNull)
            put("deployment_id", JsonNull)
            put("entity_id", JsonNull)
        }

        /** Locked invariant: block id = `block-` + sha256("<agentId>:<label>")[..24]. */
        fun blockIdFor(agentId: String, label: String): String =
            "block-" + sha256Hex("$agentId:$label").take(BLOCK_ID_HASH_CHARS)
    }
}

