package com.letta.mobile.data.schedules.runs

import com.letta.mobile.data.schedules.RunRecord
import com.letta.mobile.data.schedules.RunStatus
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/** Maps an App Server cron response to recorded runs; failed responses have no records. */
fun cronRunRecords(response: AppServerInboundFrame.CronRunsResponse): List<RunRecord> =
    if (response.success) cronRunRecords(response.page) else emptyList()

/**
 * Maps the merged `cron.runs` page, ignoring malformed rows and unknown fields. A reason without
 * a source may be an older upstream scheduler reason and is not treated as a wrapper wake.
 */
fun cronRunRecords(page: JsonObject?): List<RunRecord> =
    (page?.get("entries") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toRunRecord() }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.toRunRecord(): RunRecord? {
    val id = string("jobId")?.takeIf { it.isNotBlank() } ?: return null
    val instant = (this["ts"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?.let { millis -> runCatching { Instant.fromEpochMilliseconds(millis) }.getOrNull() } ?: return null
    val outcome = string("status") ?: string("outcome") ?: string("error")?.takeIf { it.isNotBlank() }?.let { "error" }
    val status = when (outcome) {
        "done", "ok", "success" -> RunStatus.Done
        "failed", "failure", "error" -> RunStatus.Failed
        "skipped" -> RunStatus.Skipped
        "running" -> RunStatus.Running
        "unknown" -> RunStatus.Unrecorded
        else -> return null
    }
    val source = string("source")
    return RunRecord(
        scheduleId = id,
        instant = instant,
        succeeded = status == RunStatus.Done,
        skipped = status == RunStatus.Skipped,
        error = string("error") ?: string("detail").takeIf { status == RunStatus.Failed },
        reason = string("reason")?.takeIf { source != null },
        source = source,
        recordedStatus = status,
        scheduleName = string("scheduleName"),
        recordId = string("runId"),
    )
}
