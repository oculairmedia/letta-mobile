package com.letta.mobile.data.controller.node.iroh.records

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Producer-reported outcome; unrecognized wire values become [UNKNOWN]. */
@Serializable
enum class RecordedRunStatus {
    @SerialName("done") DONE,
    @SerialName("failed") FAILED,
    @SerialName("skipped") SKIPPED,
    @SerialName("running") RUNNING,
    @SerialName("unknown") UNKNOWN,
}

/** Generic provenance, independent of the reporting peer's identity. */
@Serializable
enum class RecordedRunSource {
    @SerialName("cron") CRON,
    @SerialName("agent") AGENT,
    @SerialName("human") HUMAN,
    @SerialName("jules") JULES,
    @SerialName("unknown") UNKNOWN,
}

/** Unknown future states retain leases under the same bound as active leases. */
@Serializable
enum class RecordedLeaseState {
    @SerialName("active") ACTIVE,
    @SerialName("done") DONE,
    @SerialName("expired") EXPIRED,
    @SerialName("recovered") RECOVERED,
    @SerialName("unknown") UNKNOWN,
    ;

    /** Whether last-update retention may remove this lease. */
    val closed: Boolean get() = when (this) {
        DONE, EXPIRED, RECOVERED -> true
        ACTIVE, UNKNOWN -> false
    }
}

/** Validated event values; time is epoch milliseconds, never a persisted wire string. */
@Serializable
data class RecordedRunInput(
    val agentId: String,
    val scheduleId: String?,
    val scheduleName: String,
    val instantMs: Long,
    val status: RecordedRunStatus,
    val reason: String,
    val source: RecordedRunSource,
    val detail: String? = null,
)

/** Append-only wrapper record with a stable natural-key identity. */
@Serializable
data class StoredRunRecord(val input: RecordedRunInput, val id: String, val createdAtMs: Long)

/** Mutable lease values; owner and start are immutable after the first upsert. */
@Serializable
data class RecordedLeaseInput(
    val id: String,
    val owner: String,
    val label: String,
    val startedAtMs: Long,
    val dueAtMs: Long,
    val state: RecordedLeaseState,
    val runId: String? = null,
    val detail: String? = null,
)

/** Final lease state with first-seen and last-upsert times from the wrapper clock. */
@Serializable
data class StoredLeaseRecord(val input: RecordedLeaseInput, val createdAtMs: Long, val updatedAtMs: Long)

/** An immutable snapshot prevents readers observing a partially committed write. */
@Serializable
data class WrapperRecordSnapshot(
    val runs: List<StoredRunRecord> = emptyList(),
    val leases: List<StoredLeaseRecord> = emptyList(),
)

/** Injectable wrapper-owned persistence; implementations serialize whole write transactions. */
interface WrapperRecordStore {
    /** Append or return an identical retry; conflicting natural keys fail. */
    fun record(input: RecordedRunInput): StoredRunRecord
    /** Replace a lease's final state while preserving immutable ownership and first-seen time. */
    fun upsert(input: RecordedLeaseInput): StoredLeaseRecord
    /** Read a committed snapshot. Scope filtering belongs to the authenticated RPC boundary. */
    fun snapshot(): WrapperRecordSnapshot
}
