package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve Issue schema. */
@Serializable
@Suppress("LongParameterList") // Mirrors the pinned wire schema.
data class Issue(
    val id: String,
    val title: String,
    val description: String? = null,
    val design: String? = null,
    @SerialName("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    val notes: String? = null,
    @SerialName("spec_id")
    val specId: String? = null,
    val status: String? = null,
    val priority: Int,
    @SerialName("issue_type")
    val issueType: String? = null,
    @SerialName("is_blocked")
    val isBlocked: Boolean? = null,
    val assignee: String? = null,
    val owner: String? = null,
    @SerialName("estimated_minutes")
    val estimatedMinutes: Int? = null,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("created_by")
    val createdBy: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("started_at")
    val startedAt: String? = null,
    @SerialName("closed_at")
    val closedAt: String? = null,
    @SerialName("close_reason")
    val closeReason: String? = null,
    @SerialName("closed_by_session")
    val closedBySession: String? = null,
    @SerialName("lease_expires_at")
    val leaseExpiresAt: String? = null,
    @SerialName("heartbeat_at")
    val heartbeatAt: String? = null,
    @SerialName("lease_granted_node")
    val leaseGrantedNode: String? = null,
    @SerialName("due_at")
    val dueAt: String? = null,
    @SerialName("defer_until")
    val deferUntil: String? = null,
    @SerialName("external_ref")
    val externalRef: String? = null,
    @SerialName("source_system")
    val sourceSystem: String? = null,
    val metadata: JsonElement? = null,
    @SerialName("compaction_level")
    val compactionLevel: Int? = null,
    @SerialName("compacted_at")
    val compactedAt: String? = null,
    @SerialName("compacted_at_commit")
    val compactedAtCommit: String? = null,
    @SerialName("original_size")
    val originalSize: Int? = null,
    val labels: List<String>? = null,
    val dependencies: List<Dependency>? = null,
    val comments: List<Comment>? = null,
    val sender: String? = null,
    val ephemeral: Boolean? = null,
    @SerialName("no_history")
    val noHistory: Boolean? = null,
    @SerialName("wisp_type")
    val wispType: String? = null,
    @SerialName("storage_class")
    val storageClass: String? = null,
    val pinned: Boolean? = null,
    @SerialName("is_template")
    val isTemplate: Boolean? = null,
    @SerialName("bonded_from")
    val bondedFrom: List<BondRef>? = null,
    @SerialName("await_type")
    val awaitType: String? = null,
    @SerialName("await_id")
    val awaitId: String? = null,
    val timeout: Long? = null,
    val waiters: List<String>? = null,
    @SerialName("source_formula")
    val sourceFormula: String? = null,
    @SerialName("source_location")
    val sourceLocation: String? = null,
    @SerialName("mol_type")
    val molType: String? = null,
    @SerialName("work_type")
    val workType: String? = null,
    @SerialName("event_kind")
    val eventKind: String? = null,
    val actor: String? = null,
    val target: String? = null,
    val payload: String? = null,
)
