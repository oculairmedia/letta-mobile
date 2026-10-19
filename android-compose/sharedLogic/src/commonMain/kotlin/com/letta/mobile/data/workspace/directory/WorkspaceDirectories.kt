package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerDirectoryCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.WorkspaceRelayException
import com.letta.mobile.data.workspace.WorkspaceFileErrors
import com.letta.mobile.data.workspace.relay.WorkspaceClientRoute
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/** One page of a folder's sub-folders, by name; [hasMore] when the server held some back. */
data class DirectoryListing(
    val path: String,
    val folders: List<String>,
    val hasMore: Boolean = false,
)

/**
 *  (G32): the folders on the device the agent works in, for the
 * working-directory picker. [AppServerWorkspaceDirectorySource] serves it with `list_in_directory`.
 */
fun interface WorkspaceDirectorySource {
    /** The sub-folders of the absolute folder [path]; throws [DirectoryBrowseException]. */
    suspend fun list(path: String): DirectoryListing
}

/**
 * Creates the folder [name] inside the absolute folder [parent] and returns its absolute path;
 * throws [DirectoryBrowseException] with a message safe to show.
 *
 * letta-code has no create-folder command (protocol_v2 through 0.34.10 offers `list_in_directory`,
 * `write_file` and `edit_file`, nothing that makes a directory), so only a host that shares the
 * App Server's file system can bind one: the desktop with its bundled local runtime. Everywhere
 * else the picker shows [WorkingDirectoryPickerGates.createFolderUnavailable].
 */
fun interface FolderCreator {
    suspend fun create(parent: String, name: String): String
}

/** A browse or create-folder failure; [message] is safe to show. */
class DirectoryBrowseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the picker shows instead of a host's raw error. */
object DirectoryBrowseErrors {
    const val NOT_FOUND: String = "This folder does not exist."
    const val TIMEOUT: String = "The App Server did not answer in time."

    /**
     * Browsing needs the App Server's `list_in_directory`. An Iroh host relays only its allowlisted
     * workspace methods, and this is not one of them yet (letta-mobile follow-up for the relay).
     */
    const val BROWSE_UNAVAILABLE: String =
        "Browsing folders needs a direct App Server connection (the bundled local runtime or an App Server URL). " +
            "You can still type a path."

    const val INVALID_NAME: String = "Folder names cannot be empty, contain a slash, or be . or .."
}

/** `list_in_directory` over an App Server session or the route that picks one. */
class AppServerWorkspaceDirectorySource(
    private val client: suspend () -> AppServerClient,
    private val requestId: (String) -> String,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : WorkspaceDirectorySource {
    override suspend fun list(path: String): DirectoryListing {
        val command = AppServerDirectoryCommand.ListInDirectory(requestId("list-directory"), path, limit = pageSize)
        val frame = guarded { client().workspaceRequest(command) }.single()
        val response = runCatching { AppServerProtocol.json.decodeFromJsonElement(ListResponse.serializer(), frame) }
            .getOrElse { throw DirectoryBrowseException("The App Server sent an unreadable ${command.responseType}.", it) }
        if (!response.success) {
            val message = if (WorkspaceFileErrors.isMissingFile(response.error)) DirectoryBrowseErrors.NOT_FOUND else response.error
            throw DirectoryBrowseException(message ?: "The App Server could not list this folder.")
        }
        return DirectoryListing(path = response.path ?: path, folders = response.folders, hasMore = response.hasMore)
    }

    private suspend fun <T> guarded(call: suspend () -> T): T =
        runCatching { call() }.getOrElse { throw browseFailure(it) }

    companion object {
        /** Enough for any folder a person scrolls through; the rest are reached by typing a path. */
        const val DEFAULT_PAGE_SIZE: Int = 500
    }
}

@Serializable
private data class ListResponse(
    val path: String? = null,
    val folders: List<String> = emptyList(),
    val hasMore: Boolean = false,
    val success: Boolean = false,
    val error: String? = null,
)

/** [error] as what the picker shows; cancellation and our own failures pass through unchanged. */
private fun browseFailure(error: Throwable): Throwable = when (error) {
    is CancellationException, is DirectoryBrowseException -> error
    is AppServerRequestTimeoutException -> DirectoryBrowseException(DirectoryBrowseErrors.TIMEOUT, error)
    // Not an allowlisted Iroh relay method, or a client that cannot carry workspace commands.
    is WorkspaceRelayException, is UnsupportedOperationException ->
        DirectoryBrowseException(DirectoryBrowseErrors.BROWSE_UNAVAILABLE, error)
    else -> {
        val noConnection = error is IllegalStateException && error.message == WorkspaceClientRoute.NO_CONNECTION
        val message = if (noConnection) DirectoryBrowseErrors.BROWSE_UNAVAILABLE else error.message
        DirectoryBrowseException(message ?: "The App Server request failed.", error)
    }
}
