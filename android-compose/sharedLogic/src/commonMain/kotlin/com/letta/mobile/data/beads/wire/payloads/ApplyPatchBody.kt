package com.letta.mobile.data.beads.wire.payloads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Wire representation of the bd serve ApplyPatchBody schema. For nullable patch fields, null omits and JsonNull clears. */
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
    val labels: ApplyLabelPatch? = null,
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
