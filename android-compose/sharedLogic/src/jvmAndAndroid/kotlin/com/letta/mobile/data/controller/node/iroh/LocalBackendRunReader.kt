package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Query shape for [LocalBackendRunReader.listRuns] — mirrors admin-shim `ListRunsParams`. */
internal data class RunQuery(
    val agentId: String? = null,
    val conversationId: String? = null,
    val active: Boolean? = null,
    val background: Boolean? = null,
    val statuses: List<String> = emptyList(),
    val stopReason: String? = null,
    val before: String? = null,
    val after: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    val order: String = "desc",
    val includeArchived: Boolean = false,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/** Query shape for [LocalBackendRunReader.listSteps] — mirrors admin-shim `ListRunStepsParams`. */
internal data class StepQuery(
    val before: String? = null,
    val after: String? = null,
    val limit: Int = DEFAULT_LIMIT,
    val order: String = "desc",
) {
    companion object {
        const val DEFAULT_LIMIT = 100
    }
}

/**
 * lgns8.9: on-disk run/step reader — the native owner for `run.list`,
 * `run.get`, and `step.list`.
 *
 * Faithful port of admin-shim `lib/runs.ts` (`listRuns`, `getRun`,
 * `listRunSteps`) plus the thin HTTP wrappers in `server.ts`
 * (`handleRunsList`, `handleRunDetail`, `handleRunSteps`). The store layout is
 * flat and already carries the wire shape:
 *
 * ```
 * <baseDir>/runs/<runId>/run.json      # the Run object, verbatim
 * <baseDir>/runs/<runId>/steps.jsonl   # one Step object per line
 * <baseDir>/runs/_archive/<runId>/…    # retention-aged terminal runs
 * ```
 *
 * `run.json` is emitted as read, so no re-projection can drift from the wire
 * contract mobile already decodes. Locked behaviours ported verbatim:
 *  - the live walk NEVER descends `_archive` (admin-shim `lcp-98cm`), but
 *    `run.get` still resolves an archived run by id, so no history is lost;
 *  - ordering is by `created_at` string compare, desc by default;
 *  - `after`/`before` are id cursors applied AFTER sorting;
 *  - `limit` is clamped to at least 1.
 *
 * READ-ONLY by construction: no method here opens a file for writing.
 */
internal class LocalBackendRunReader(private val support: LocalBackendStoreSupport) {

    /** Port of `GET /v1/runs`. Returns null on any error so the caller fails closed. */
    fun listRuns(query: RunQuery): JsonArray? = runCatching {
        val matches = ArrayList<JsonObject>()
        walk(runsRoot(), matches, query)
        if (query.includeArchived) walk(archiveRoot(), matches, query)
        val ascending = query.order.lowercase() == "asc"
        matches.sortWith(
            compareBy<JsonObject> { it["created_at"]?.stringOrNull() ?: "" }
                .let { if (ascending) it else it.reversed() },
        )
        val scoped = applyCursors(matches, after = query.after, before = query.before) { it["id"]?.stringOrNull() }
        buildJsonArray { scoped.take(query.limit.coerceAtLeast(1)).forEach { add(it) } }
    }.getOrNull()

    /**
     * Port of `getRun`: resolve by id in the live root, then the archive. The
     * in-memory `_activeRuns` fast path has no analogue for a cold reader — a
     * run in flight has already been persisted to `run.json` by the writer.
     */
    fun getRun(runId: String): JsonObject? = runCatching {
        readRun(File(File(runsRoot(), runId), RUN_FILE))
            ?: readRun(File(File(archiveRoot(), runId), RUN_FILE))
    }.getOrNull()

    /** Port of `listRunSteps`: parse steps.jsonl, sort by created_at, apply cursors + limit. */
    fun listSteps(runId: String, query: StepQuery): JsonArray? = runCatching {
        val dir = File(runsRoot(), runId).takeIf { it.isDirectory } ?: File(archiveRoot(), runId)
        val file = File(dir, STEPS_FILE)
        if (!file.isFile) return@runCatching JsonArray(emptyList())
        val steps = ArrayList<JsonObject>()
        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachLine
            val obj = runCatching { support.json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            if (obj != null && obj["id"]?.stringOrNull() != null) steps += obj
        }
        val ascending = query.order.lowercase() == "asc"
        steps.sortWith(
            compareBy<JsonObject> { it["created_at"]?.stringOrNull() ?: "" }
                .let { if (ascending) it else it.reversed() },
        )
        val scoped = applyCursors(steps, after = query.after, before = query.before) { it["id"]?.stringOrNull() }
        buildJsonArray { scoped.take(query.limit.coerceAtLeast(1)).forEach { add(it) } }
    }.getOrNull()

    /** True when the run exists at all — `step.list` 404s in admin-shim for an unknown run. */
    fun runExists(runId: String): Boolean =
        File(File(runsRoot(), runId), RUN_FILE).isFile || File(File(archiveRoot(), runId), RUN_FILE).isFile

    private fun walk(root: File, into: MutableList<JsonObject>, query: RunQuery) {
        val dirs = root.listFiles { f -> f.isDirectory && f.name != ARCHIVE_DIR } ?: return
        for (dir in dirs) {
            val run = readRun(File(dir, RUN_FILE)) ?: continue
            if (matches(run, query)) into += run
        }
    }

    private fun matches(run: JsonObject, query: RunQuery): Boolean {
        val status = run["status"]?.stringOrNull()
        if (query.agentId != null && run["agent_id"]?.stringOrNull() != query.agentId) return false
        if (query.conversationId != null && run["conversation_id"]?.stringOrNull() != query.conversationId) return false
        if (query.active == true && status != RUNNING) return false
        if (query.active == false && status == RUNNING) return false
        if (query.background != null && run["background"]?.booleanOrNull() != query.background) return false
        if (query.statuses.isNotEmpty() && status !in query.statuses) return false
        if (query.stopReason != null && run["stop_reason"]?.stringOrNull() != query.stopReason) return false
        return true
    }

    /** Drop everything up to and including `after`, then everything from `before` on (both exclusive of the cursor row). */
    private fun applyCursors(
        items: List<JsonObject>,
        after: String?,
        before: String?,
        id: (JsonObject) -> String?,
    ): List<JsonObject> {
        var scoped = items
        if (!after.isNullOrEmpty()) {
            val idx = scoped.indexOfFirst { id(it) == after }
            if (idx >= 0) scoped = scoped.subList(idx + 1, scoped.size)
        }
        if (!before.isNullOrEmpty()) {
            val idx = scoped.indexOfFirst { id(it) == before }
            if (idx >= 0) scoped = scoped.subList(0, idx)
        }
        return scoped
    }

    private fun readRun(file: File): JsonObject? = runCatching {
        file.takeIf { it.isFile }?.readText()?.let { support.json.parseToJsonElement(it).jsonObject }
    }.getOrNull()?.takeIf { it["id"]?.stringOrNull() != null }

    private fun runsRoot(): File = File(support.baseDir, "runs")

    private fun archiveRoot(): File = File(runsRoot(), ARCHIVE_DIR)

    companion object {
        private const val ARCHIVE_DIR = "_archive"
        private const val RUN_FILE = "run.json"
        private const val STEPS_FILE = "steps.jsonl"
        private const val RUNNING = "running"
    }
}

internal fun kotlinx.serialization.json.JsonElement.booleanOrNull(): Boolean? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
