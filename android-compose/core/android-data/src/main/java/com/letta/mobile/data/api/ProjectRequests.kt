package com.letta.mobile.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The body of a `project.create` request (VibeSync, relayed by an Iroh host's `admin_rpc`). */
@Serializable
data class ProjectCreateRequest(
    val name: String? = null,
    @SerialName("filesystem_path") val filesystemPath: String,
    @SerialName("git_url") val gitUrl: String? = null,
)

/** The body of a `project.update` request (VibeSync, relayed by an Iroh host's `admin_rpc`). */
@Serializable
data class ProjectUpdateRequest(
    val status: String? = null,
    @SerialName("filesystem_path") val filesystemPath: String? = null,
    @SerialName("git_url") val gitUrl: String? = null,
)
