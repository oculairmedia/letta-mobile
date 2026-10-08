package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-bzvro.24: MemFS browsing commands (letta-code `list_memory`, `read_memory_file`,
 * `memory_history`, `memory_commit_diff`, `memory_file_at_ref`, `enable_memfs`). Writes and
 * deletes keep their dedicated [AppServerCommand.WriteMemoryFile] / [AppServerCommand.DeleteMemoryFile].
 * Paths are relative to the agent's memory root; upstream rejects any that escape it.
 */
@Serializable
sealed interface AppServerMemfsCommand : AppServerWorkspaceCommand {
    val agentId: String

    override val isRead: Boolean get() = true

    /** Every memory file, five per `list_memory_response` page; the last page has `done: true`. */
    @Serializable
    @SerialName("list_memory")
    data class ListMemory(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
        @SerialName("include_references") val includeReferences: Boolean? = null,
    ) : AppServerMemfsCommand {
        override val responseType: String get() = "list_memory_response"
        override val isStreamed: Boolean get() = true
    }

    /** The whole file as stored (frontmatter included), unlike a listing entry's body. */
    @Serializable
    @SerialName("read_memory_file")
    data class ReadMemoryFile(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
        val path: String,
        val encoding: String? = null,
    ) : AppServerMemfsCommand {
        override val responseType: String get() = "read_memory_file_response"
    }

    /** Commits touching [filePath], newest first; every commit when [filePath] is null. */
    @Serializable
    @SerialName("memory_history")
    data class MemoryHistory(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
        @SerialName("file_path") val filePath: String? = null,
        val limit: Int? = null,
    ) : AppServerMemfsCommand {
        override val responseType: String get() = "memory_history_response"
    }

    /** The commit's patch (`git show <sha>`), every file it touched. */
    @Serializable
    @SerialName("memory_commit_diff")
    data class MemoryCommitDiff(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
        val sha: String,
    ) : AppServerMemfsCommand {
        override val responseType: String get() = "memory_commit_diff_response"
    }

    /** One file's content at [ref]. */
    @Serializable
    @SerialName("memory_file_at_ref")
    data class MemoryFileAtRef(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
        @SerialName("file_path") val filePath: String,
        val ref: String,
    ) : AppServerMemfsCommand {
        override val responseType: String get() = "memory_file_at_ref_response"
    }

    /** Turns MemFS on for an agent that has none; the server follows with `memory_updated`. */
    @Serializable
    @SerialName("enable_memfs")
    data class EnableMemfs(
        @SerialName("request_id") override val requestId: String,
        @SerialName("agent_id") override val agentId: String,
    ) : AppServerMemfsCommand {
        override val responseType: String get() = "enable_memfs_response"
        override val isRead: Boolean get() = false
    }
}
