package com.letta.mobile.data.memory.memfs

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerMemfsCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [MemfsSource] over an App Server connection (letta-mobile-bzvro.24): the letta-code MemFS
 * commands, plus `write_memory_file` for saves. [events] is the connection's inbound frames, from
 * which the `memory_updated` pushes are read.
 */
class AppServerMemfsSource(
    private val client: suspend () -> AppServerClient,
    events: Flow<AppServerReceivedFrame>,
    private val requestId: (String) -> String,
) : MemfsSource {
    override val updates: Flow<MemfsUpdate> = events.mapNotNull { received -> memoryUpdate(received.frame) }

    override suspend fun list(agentId: String): MemfsListing {
        val pages = call(AppServerMemfsCommand.ListMemory(requestId("list-memory"), agentId), ListMemoryPage.serializer())
        val enabled = pages.all { it.memfsEnabled != false }
        return MemfsListing(enabled = enabled, files = pages.flatMap { page -> page.entries.map(MemoryEntry::toFile) })
    }

    override suspend fun read(file: MemfsFileRef): String =
        single(AppServerMemfsCommand.ReadMemoryFile(requestId("read-memory-file"), file.agentId, file.path), ContentResponse.serializer())
            .content.orEmpty()

    override suspend fun write(file: MemfsFileRef, content: String): String? {
        val response = guarded {
            client().writeMemoryFile(
                AppServerCommand.WriteMemoryFile(requestId = requestId("write-memory-file"), agentId = file.agentId, path = file.path, content = content),
            )
        }
        if (!response.success) throw MemfsException(response.error ?: "The memory file could not be saved.")
        return response.commitSha
    }

    override suspend fun history(scope: MemfsHistoryScope): List<MemfsCommit> =
        single(AppServerMemfsCommand.MemoryHistory(requestId("memory-history"), scope.agentId, filePath = scope.path), HistoryResponse.serializer())
            .commits.map { MemfsCommit(sha = it.sha, message = it.message, timestamp = it.timestamp, author = it.authorName) }

    override suspend fun commitDiff(commit: MemfsCommitRef): String =
        single(AppServerMemfsCommand.MemoryCommitDiff(requestId("memory-commit-diff"), commit.agentId, commit.sha), DiffResponse.serializer())
            .diff.orEmpty()

    override suspend fun fileAtRef(file: MemfsFileRef, ref: String): String =
        single(AppServerMemfsCommand.MemoryFileAtRef(requestId("memory-file-at-ref"), file.agentId, file.path, ref), ContentResponse.serializer())
            .content.orEmpty()

    override suspend fun enable(agentId: String) {
        single(AppServerMemfsCommand.EnableMemfs(requestId("enable-memfs"), agentId), ContentResponse.serializer())
    }

    private suspend fun <T : MemfsResponse> single(command: AppServerWorkspaceCommand, serializer: KSerializer<T>): T =
        call(command, serializer).single()

    /** Sends [command] and decodes every answering frame; any `success: false` fails the call. */
    private suspend fun <T : MemfsResponse> call(command: AppServerWorkspaceCommand, serializer: KSerializer<T>): List<T> {
        val frames = guarded { client().workspaceRequest(command) }
        return frames.map { frame ->
            val response = runCatching { AppServerProtocol.json.decodeFromJsonElement(serializer, frame) }
                .getOrElse { throw MemfsException("The App Server sent an unreadable ${command.responseType}.", it) }
            if (!response.success) throw MemfsException(response.error ?: "The App Server refused ${command.responseType}.")
            response
        }
    }

    private suspend fun <T> guarded(call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (memfs: MemfsException) {
        throw memfs
    } catch (timeout: AppServerRequestTimeoutException) {
        throw MemfsException("The App Server did not answer in time.", timeout)
    } catch (unsupported: UnsupportedOperationException) {
        throw MemfsException("This connection cannot browse memory files.", unsupported)
    } catch (error: Exception) {
        throw MemfsException(error.message ?: "The App Server request failed.", error)
    }

    private fun memoryUpdate(frame: AppServerInboundFrame): MemfsUpdate? {
        val unknown = frame as? AppServerInboundFrame.Unknown ?: return null
        if (unknown.type != MEMORY_UPDATED) return null
        val pushed = runCatching { AppServerProtocol.json.decodeFromJsonElement(MemoryUpdated.serializer(), unknown.raw) }.getOrNull()
        val paths = pushed?.affectedPaths.orEmpty().map { it.replace('\\', '/') }.toSet()
        return MemfsUpdate(paths.ifEmpty { setOf(MemfsUpdate.WILDCARD) })
    }

    private companion object {
        const val MEMORY_UPDATED = "memory_updated"
    }
}

private interface MemfsResponse {
    val success: Boolean
    val error: String?
}

@Serializable
private data class ListMemoryPage(
    val entries: List<MemoryEntry> = emptyList(),
    override val success: Boolean = false,
    override val error: String? = null,
    @SerialName("memfs_enabled") val memfsEnabled: Boolean? = null,
) : MemfsResponse

@Serializable
private data class MemoryEntry(
    @SerialName("relative_path") val relativePath: String,
    @SerialName("is_system") val isSystem: Boolean = false,
    val description: String? = null,
    val content: String = "",
    val size: Long = 0,
    val kind: String? = null,
) {
    fun toFile(): MemfsFile = MemfsFile(
        path = relativePath.replace('\\', '/'),
        isSystem = isSystem,
        description = description,
        sizeBytes = size,
        kind = if (kind == "image") MemfsFileKind.Image else MemfsFileKind.Markdown,
        body = content,
    )
}

/** `read_memory_file_response`, `memory_file_at_ref_response` and `enable_memfs_response`. */
@Serializable
private data class ContentResponse(
    val content: String? = null,
    override val success: Boolean = false,
    override val error: String? = null,
) : MemfsResponse

@Serializable
private data class HistoryResponse(
    val commits: List<HistoryCommit> = emptyList(),
    override val success: Boolean = false,
    override val error: String? = null,
) : MemfsResponse

@Serializable
private data class HistoryCommit(
    val sha: String,
    val message: String = "",
    val timestamp: String = "",
    @SerialName("author_name") val authorName: String? = null,
)

@Serializable
private data class DiffResponse(
    val diff: String? = null,
    override val success: Boolean = false,
    override val error: String? = null,
) : MemfsResponse

@Serializable
private data class MemoryUpdated(
    @SerialName("affected_paths") val affectedPaths: List<String> = emptyList(),
)
