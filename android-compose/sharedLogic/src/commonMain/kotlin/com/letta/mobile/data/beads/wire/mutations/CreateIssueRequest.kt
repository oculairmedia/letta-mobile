package com.letta.mobile.data.beads.wire.mutations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve CreateIssueRequest schema. */
@Serializable
@Suppress("LongParameterList") // Mirrors the pinned wire schema.
data class CreateIssueRequest(
    val actor: String,
    val id: String? = null,
    val title: String,
    val description: String? = null,
    val design: String? = null,
    @SerialName("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    val notes: String? = null,
    @SerialName("issue_type")
    val issueType: String? = null,
    val status: String? = null,
    val priority: Int? = null,
    val assignee: String? = null,
    val owner: String? = null,
    val labels: List<String>? = null,
    @SerialName("estimated_minutes")
    val estimatedMinutes: Int? = null,
    @SerialName("external_ref")
    val externalRef: String? = null,
    @SerialName("due_at")
    val dueAt: String? = null,
    @SerialName("defer_until")
    val deferUntil: String? = null,
    val sender: String? = null,
    val metadata: JsonElement? = null,
    val ephemeral: Boolean? = null,
    @SerialName("no_history")
    val noHistory: Boolean? = null,
    @SerialName("parent_id")
    val parentId: String? = null,
    @SerialName("inherit_labels_from_parent")
    val inheritLabelsFromParent: Boolean? = null,
    val dependencies: List<CreateIssueDependency>? = null,
    @SerialName("waits_for")
    val waitsFor: CreateIssueWaitsFor? = null,
    @SerialName("force_id_prefix")
    val forceIdPrefix: Boolean? = null,
)
