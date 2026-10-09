package com.letta.mobile.data.workspace

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerFileCommand
import com.letta.mobile.data.transport.appserver.AppServerMemfsCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * [WorkspaceFileSource] over an App Server connection (letta-mobile-bzvro.26): `search_files` for
 * `@` mentions and strict-UTF-8 `read_file` for the viewer. A file the server cannot decode as
 * UTF-8 is [WorkspaceFileContent.Binary]; one over [maxViewChars] is [WorkspaceFileContent.TooLarge].
 */
class AppServerWorkspaceFileSource(
    private val client: suspend () -> AppServerClient,
    private val requestId: (String) -> String,
    private val maxViewChars: Int = DEFAULT_MAX_VIEW_CHARS,
) : WorkspaceFileSource {
    override suspend fun search(query: String, cwd: String?, limit: Int): List<String> =
        call(AppServerFileCommand.SearchFiles(requestId("search-files"), query, maxResults = limit, cwd = cwd), SearchResponse.serializer())
            .files.map { it.path.replace('\\', '/') }

    override suspend fun read(path: String): WorkspaceFileContent {
        val frame = guarded { client().workspaceRequest(AppServerFileCommand.ReadFile(requestId("read-file"), path)) }.single()
        return content(path, decode(frame, ReadResponse.serializer(), "read_file_response"))
    }

    /** `read_memory_file`: the server joins [path] to [agentId]'s memory root (letta-mobile-bzvro.37). */
    override suspend fun readMemory(agentId: String, path: String): WorkspaceFileContent {
        val command = AppServerMemfsCommand.ReadMemoryFile(requestId("read-memory-file"), agentId, path)
        val frame = guarded { client().workspaceRequest(command) }.single()
        return content(path, decode(frame, ReadResponse.serializer(), command.responseType))
    }

    private fun content(path: String, response: ReadResponse): WorkspaceFileContent {
        val text = response.content
        return when {
            !response.success && response.error.orEmpty().startsWith(NOT_UTF8) -> WorkspaceFileContent.Binary(path)
            !response.success && response.isMissingFile -> throw WorkspaceFileException(WorkspaceFileErrors.NOT_FOUND)
            !response.success -> throw WorkspaceFileException(response.error ?: "The file could not be read.")
            text == null -> WorkspaceFileContent.Binary(path)
            text.length > maxViewChars -> WorkspaceFileContent.TooLarge(path, text.length)
            NUL in text -> WorkspaceFileContent.Binary(path)
            else -> WorkspaceFileContent.Text(path, text)
        }
    }

    private suspend fun <T : FileResponse> call(command: AppServerWorkspaceCommand, serializer: KSerializer<T>): T {
        val frame = guarded { client().workspaceRequest(command) }.single()
        val response = decode(frame, serializer, command.responseType)
        if (!response.success) throw WorkspaceFileException(response.error ?: "The App Server refused ${command.responseType}.")
        return response
    }

    private fun <T> decode(frame: JsonObject, serializer: KSerializer<T>, type: String): T =
        runCatching { AppServerProtocol.json.decodeFromJsonElement(serializer, frame) }
            .getOrElse { throw WorkspaceFileException("The App Server sent an unreadable $type.", it) }

    private suspend fun <T> guarded(call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (timeout: AppServerRequestTimeoutException) {
        throw WorkspaceFileException("The App Server did not answer in time.", timeout)
    } catch (unsupported: UnsupportedOperationException) {
        throw WorkspaceFileException("This connection cannot read workspace files.", unsupported)
    } catch (error: Exception) {
        throw WorkspaceFileException(error.message ?: "The App Server request failed.", error)
    }

    companion object {
        /** About 400 KB of text: beyond it the viewer would stall rather than help. */
        const val DEFAULT_MAX_VIEW_CHARS: Int = 400_000
        private const val NOT_UTF8 = "File is not valid UTF-8 text"
        private const val NUL = '\u0000'
    }
}

private interface FileResponse {
    val success: Boolean
    val error: String?
}

@Serializable
private data class SearchResponse(
    val files: List<SearchEntry> = emptyList(),
    override val success: Boolean = false,
    override val error: String? = null,
) : FileResponse

@Serializable
private data class SearchEntry(val path: String)

/** `read_file_response` and `read_memory_file_response`. */
@Serializable
private data class ReadResponse(
    val content: String? = null,
    override val success: Boolean = false,
    override val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
) : FileResponse {
    /** A missing file: the relay's typed code, or a raw `ENOENT` from a direct session. */
    val isMissingFile: Boolean
        get() = errorCode == WorkspaceFileErrors.NOT_FOUND_CODE || WorkspaceFileErrors.isMissingFile(error)
}
