package com.letta.mobile.data.workspace

import androidx.compose.runtime.Immutable

/**
 * The device workspace the agent works in, read-only (letta-mobile-bzvro.26): what `@` mentions
 * search and the file viewer reads. [AppServerWorkspaceFileSource] serves it over an App Server
 * connection; tests fake it. The working directory comes from the caller: today the
 * conversation's cwd, later the typed device status (F19, letta-mobile-bzvro.19).
 */
interface WorkspaceFileSource {
    /** Up to [limit] paths containing [query], relative to [cwd] (the server's directory when null). */
    suspend fun search(query: String, cwd: String?, limit: Int): List<String>

    /** The file at absolute [path] as text, or a reason it cannot be shown. */
    suspend fun read(path: String): WorkspaceFileContent

    /**
     * letta-mobile-bzvro.37: [path] relative to [agentId]'s memory root (a memory tool's
     * `system/human/…`). The server joins it to that agent's MemFS root; it never resolves
     * against the host process's working directory.
     */
    suspend fun readMemory(agentId: String, path: String): WorkspaceFileContent =
        throw WorkspaceFileException(WorkspaceFileErrors.NOT_SUPPORTED)
}

/** Messages the viewer shows instead of a host's raw error text. */
object WorkspaceFileErrors {
    const val NOT_FOUND: String = "This file does not exist."

    /** A source that cannot read an agent's memory files at all. */
    const val NOT_SUPPORTED: String = "This connection cannot read memory files."

    /** A relative path from a tool that is not a memory tool, with no working directory to resolve it. */
    const val NO_WORKING_DIRECTORY: String = "This path cannot be opened without a working directory."

    /** The error code the Iroh workspace relay puts on a missing-file answer. */
    const val NOT_FOUND_CODE: String = com.letta.mobile.data.transport.appserver.WorkspaceRelay.NOT_FOUND_CODE

    /** A Node `ENOENT` / "no such file" error: a missing file, whatever path it names. */
    fun isMissingFile(error: String?): Boolean =
        error != null && (error.contains("ENOENT") || error.contains("no such file", ignoreCase = true))
}

/** What the viewer can show for one file. */
@Immutable
sealed interface WorkspaceFileContent {
    val path: String

    data class Text(override val path: String, val text: String) : WorkspaceFileContent

    /** Larger than the viewer shows; [sizeChars] is how large. */
    data class TooLarge(override val path: String, val sizeChars: Int) : WorkspaceFileContent

    /** Not UTF-8 text: an image, an archive, a compiled file. */
    data class Binary(override val path: String) : WorkspaceFileContent
}

/** A workspace request the server refused or could not answer; [message] is safe to show. */
class WorkspaceFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Path helpers that work for the POSIX and Windows paths a device may report. */
object WorkspacePaths {
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    fun isAbsolute(path: String): Boolean =
        path.startsWith('/') || path.startsWith("\\\\") || WINDOWS_DRIVE.containsMatchIn(path)

    /** [path] as an absolute path: itself when already absolute, else under [cwd]. */
    fun resolve(path: String, cwd: String?): String {
        if (isAbsolute(path) || cwd.isNullOrBlank()) return path
        val separator = if (cwd.contains('\\') && !cwd.contains('/')) '\\' else '/'
        val relative = path.removePrefix("./").replace(if (separator == '/') '\\' else '/', separator)
        return cwd.trimEnd('/', '\\') + separator + relative
    }

    fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /** The folder part of [path], or empty for a bare name. */
    fun folder(path: String): String {
        val name = fileName(path)
        return path.trimEnd('/', '\\').removeSuffix(name).trimEnd('/', '\\')
    }

    /** A highlighting hint from the extension (`kt`, `py`, `json`…), or null. */
    fun languageHint(path: String): String? =
        fileName(path).substringAfterLast('.', missingDelimiterValue = "").lowercase().takeIf { it.isNotEmpty() }
}
