package com.letta.mobile.data.beads.wire.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire representation of the bd serve ContextResponse schema. */
@Serializable
data class ContextResponse(
    @SerialName("api_version")
    val apiVersion: String,
    @SerialName("bd_version")
    val bdVersion: String,
    @SerialName("schema_version")
    val schemaVersion: Int,
    val backend: String,
    @SerialName("dolt_mode")
    val doltMode: String,
    val database: String,
    @SerialName("beads_dir")
    val beadsDir: String? = null,
    @SerialName("repo_root")
    val repoRoot: String? = null,
    @SerialName("project_id")
    val projectId: String,
    val capabilities: List<String>,
)
