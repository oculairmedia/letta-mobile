package com.letta.mobile.data.beads.wire.mutations

import com.letta.mobile.data.beads.wire.payloads.ApplyMetadataPatch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve IssuePatchBody schema. For nullable patch fields, null omits and JsonNull clears. */
@Serializable
@Suppress("LongParameterList") // Mirrors the pinned wire schema.
data class IssuePatchBody(
    val title: String? = null,
    val description: String? = null,
    val design: String? = null,
    @SerialName("acceptance_criteria")
    val acceptanceCriteria: String? = null,
    val notes: String? = null,
    @SerialName("append_notes")
    val appendNotes: String? = null,
    val priority: Int? = null,
    @SerialName("issue_type")
    val issueType: String? = null,
    val status: String? = null,
    val assignee: String? = null,
    @SerialName("parent_id")
    val parentId: String? = null,
    val labels: List<String>? = null,
    @SerialName("add_labels")
    val addLabels: List<String>? = null,
    @SerialName("remove_labels")
    val removeLabels: List<String>? = null,
    @SerialName("estimated_minutes")
    val estimatedMinutes: JsonElement? = null,
    @SerialName("external_ref")
    val externalRef: JsonElement? = null,
    @SerialName("due_at")
    val dueAt: JsonElement? = null,
    @SerialName("defer_until")
    val deferUntil: JsonElement? = null,
    val metadata: ApplyMetadataPatch? = null,
)
