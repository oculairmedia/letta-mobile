package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-bzvro.26: read-only access to the device workspace the agent works in (letta-code
 * `search_files`, `read_file`). Both are reads; neither is scoped to an agent.
 */
@Serializable
sealed interface AppServerFileCommand : AppServerWorkspaceCommand {
    override val isRead: Boolean get() = true

    /**
     * Files whose path contains [query], relative to [cwd] (the server's own directory when null);
     * an empty query returns the most recently modified files.
     */
    @Serializable
    @SerialName("search_files")
    data class SearchFiles(
        @SerialName("request_id") override val requestId: String,
        val query: String,
        @SerialName("max_results") val maxResults: Int? = null,
        val cwd: String? = null,
    ) : AppServerFileCommand {
        override val responseType: String get() = "search_files_response"
    }

    /** One file by absolute [path]; `utf8` (strict) unless [encoding] asks for `base64`. */
    @Serializable
    @SerialName("read_file")
    data class ReadFile(
        @SerialName("request_id") override val requestId: String,
        val path: String,
        val encoding: String? = null,
    ) : AppServerFileCommand {
        override val responseType: String get() = "read_file_response"
    }
}
