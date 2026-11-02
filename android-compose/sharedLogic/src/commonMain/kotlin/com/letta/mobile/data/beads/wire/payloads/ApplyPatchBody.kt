package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Shared patch fields. Null omits a field; JsonNull clears nullable members.
 * Labels retain each operation's JSON shape.
 */
@Serializable
@Suppress("LongParameterList") // Mirrors the pinned wire schema.
data class ApplyPatchBody(
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
    val owner: String? = null,
    @SerialName("parent_id")
    val parentId: String? = null,
    @SerialName("add_labels")
    val addLabels: List<String>? = null,
    @SerialName("remove_labels")
    val removeLabels: List<String>? = null,
    val labels: JsonElement? = null,
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
