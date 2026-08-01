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

    /**
     * Port of `GET /v1/blocks`: union of `readBlocksForAgent` over every agent,
     * **windowed** by [offset]/[limit].
     *
     * letta-mobile post-cutover regression (2026-08-01): the unpaged union on the
     * live host is 153 agents x 1447 memory files = ~1.83 MB of JSON, which is
     * larger than `IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES` (1 MiB). A peer without
     * the `frame_part` capability got the typed "response too large" envelope and
     * the Block Library rendered nothing; a peer with it paid multi-MB of relayed
     * QUIC per refresh. The window is therefore mandatory, and the caller pages
     * (see `IrohAdminRpcBlockSource.listAllBlocks`).
     *
     * The window is applied over the FLAT (agentId, label) index — enumerating
     * labels never reads file contents — so only the blocks actually served are
     * read off disk. Ordering is the same deterministic mtime-desc agent order the
     * agent list serves, with labels sorted inside each agent, so a cursor sweep
     * over successive offsets is stable.
     */
    fun listAllBlocks(limit: Int?, offset: Int?): JsonArray? = runCatching {
        val index = blockIndex()
        val from = (offset ?: 0).coerceAtLeast(0)
        val window = if (from >= index.size) {
            emptyList()
        } else {
            val end = if (limit != null) (from + limit.coerceAtLeast(0)).coerceAtMost(index.size) else index.size
            index.subList(from, end)
        }
        buildJsonArray {
            var budget = PAGE_BYTE_BUDGET
            for ((agentId, label) in window) {
                val value = readBlockValue(agentId, label)
                // Block VALUES are whole files and are not truncated on read: one
                // page of 50 measured 394 KB on the live store, so a pathological
                // page could still cross the 1 MiB frame cap on count alone. Stop
                // short rather than emit an undeliverable frame — the handler's
                // has_more is computed from the page LENGTH, so a short page pages
                // correctly instead of silently dropping the remainder.
                if (budget <= 0) break
                add(projectBlock(agentId, label, value))
                budget -= value.length + PROJECTION_OVERHEAD_BYTES
            }
        }
    }.getOrNull()

    /**
     * Port of `GET /v1/blocks/{id}`: first agent whose synthesised block id matches.
     *
     * Resolved against the flat index so exactly ONE memory file is read — the id
     * is `sha256("<agentId>:<label>")`, which needs no file contents to compute.
     */
    fun getBlock(blockId: String): JsonObject? = runCatching {
        blockIndex()
            .firstOrNull { (agentId, label) -> blockIdFor(agentId, label) == blockId }
            ?.let { (agentId, label) -> projectBlock(agentId, label, readBlockValue(agentId, label)) }
    }.getOrNull()

    /** Total number of blocks the union would contain; used for paging metadata. */
    fun blockCount(): Int = runCatching { blockIndex().size }.getOrDefault(0)

    /**
     * Flat (agentId, label) enumeration in serve order. Contents are NOT read.
     *
     * Memoised for [INDEX_TTL_MS] because a cursor sweep would otherwise re-parse
     * every agent record under `agents/` once per page (1147 files x 29 pages on
     * the live host). The TTL is deliberately short: block values are always read
     * fresh, so a stale index can only delay a newly created label by seconds.
     */
    private fun blockIndex(): List<Pair<String, String>> {
        val cached = indexCache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.builtAtMs < INDEX_TTL_MS) return cached.entries
        val entries = agentIds().flatMap { agentId ->
            labelsForAgent(agentId).map { label -> agentId to label }
        }
        indexCache = BlockIndex(entries, now)
        return entries
    }

    @Volatile
    private var indexCache: BlockIndex? = null

    private class BlockIndex(val entries: List<Pair<String, String>>, val builtAtMs: Long)

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

        /** Memoisation window for the flat (agentId, label) index. */
        private const val INDEX_TTL_MS: Long = 5_000

        /**
         * Soft byte budget per page, ~half the 1 MiB frame cap so the JSON
         * envelope and escaping cannot push a page over it.
         */
        private const val PAGE_BYTE_BUDGET: Int = 512 * 1024

        /** Rough constant cost of the surrounding Block projection fields. */
        private const val PROJECTION_OVERHEAD_BYTES: Int = 400

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

