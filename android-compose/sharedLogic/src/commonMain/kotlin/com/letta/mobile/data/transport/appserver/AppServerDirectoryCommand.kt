package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-zxk2n.6 (G32): folder browsing on the device the agent works in (letta-code
 * `list_in_directory`). A read; not scoped to an agent.
 *
 * Not one of the Iroh workspace relay's methods ([WorkspaceRelayMethod]): over a relay it fails
 * with "not relayed over Iroh" until a host relays it, and the picker says so.
 */
@Serializable
sealed interface AppServerDirectoryCommand : AppServerWorkspaceCommand {
    override val isRead: Boolean get() = true

    /**
     * The entries of the absolute folder [path]: sub-folder names (and file names when
     * [includeFiles]), at most [limit] after skipping [offset]. The server answers with the bare
     * names, sorted, minus the ones it always hides (`.git`, `node_modules`, `.lettaignore`d).
     */
    @Serializable
    @SerialName("list_in_directory")
    data class ListInDirectory(
        @SerialName("request_id") override val requestId: String,
        val path: String,
        @SerialName("include_files") val includeFiles: Boolean? = null,
        val limit: Int? = null,
        val offset: Int? = null,
    ) : AppServerDirectoryCommand {
        override val responseType: String get() = "list_in_directory_response"
    }
}
