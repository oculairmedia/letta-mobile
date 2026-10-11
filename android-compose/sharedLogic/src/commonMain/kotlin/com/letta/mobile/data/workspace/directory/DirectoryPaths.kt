package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.workspace.WorkspacePaths

/**
 * Folder-path arithmetic for POSIX and Windows paths as a device reports them, independent of
 * the platform this runs on (the device may be another machine).
 */
object DirectoryPaths {
    private val WINDOWS_DRIVE_ROOT = Regex("^[A-Za-z]:[\\\\/]?$")

    /** [path] trimmed, without a trailing separator unless it is a root (`/`, `C:\`). */
    fun normalize(path: String): String {
        val trimmed = path.trim()
        if (trimmed == "/" || trimmed == "\\") return trimmed
        if (WINDOWS_DRIVE_ROOT.matches(trimmed)) return trimmed.take(2) + separatorOf(trimmed)
        return trimmed.trimEnd('/', '\\').ifEmpty { trimmed.take(1) }
    }

    fun isAbsolute(path: String): Boolean = WorkspacePaths.isAbsolute(path.trim())

    /** True at a file-system root, which has no parent. */
    fun isRoot(path: String): Boolean {
        val normalized = normalize(path)
        return normalized == "/" || normalized == "\\" || WINDOWS_DRIVE_ROOT.matches(normalized)
    }

    /** The folder containing [path], or null at a root. */
    fun parent(path: String): String? {
        val normalized = normalize(path)
        if (isRoot(normalized)) return null
        val cut = normalized.lastIndexOfAny(charArrayOf('/', '\\'))
        return when {
            cut < 0 -> null
            cut == 0 -> normalized.take(1)
            // `C:\Users` -> `C:\`
            cut == 2 && normalized[1] == ':' -> normalized.take(3)
            else -> normalized.substring(0, cut)
        }
    }

    /** [name] inside the folder [parent], with [parent]'s own separator. */
    fun child(parent: String, name: String): String {
        val base = normalize(parent)
        val separator = separatorOf(base)
        return if (base.endsWith('/') || base.endsWith('\\')) base + name else base + separator + name
    }

    /** The last segment of [path]; the path itself at a root. */
    fun name(path: String): String {
        val normalized = normalize(path)
        if (isRoot(normalized)) return normalized
        return normalized.substringAfterLast('/').substringAfterLast('\\')
    }

    /** A folder name the picker may create: one segment, not `.`/`..`, no separator or NUL. */
    fun isValidFolderName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." &&
            trimmed.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    /** Two paths name the same folder, ignoring a trailing separator. */
    fun same(a: String?, b: String?): Boolean = a != null && b != null && normalize(a) == normalize(b)

    private fun separatorOf(path: String): Char = if (path.contains('\\') && !path.contains('/')) '\\' else '/'
}

/** Where a working directory sits relative to a letta-code worktree. */
data class WorktreeLocation(
    /** The repository folder the worktree belongs to (where `.letta/worktrees` lives). */
    val root: String,
    /** The worktree's folder name under `.letta/worktrees`. */
    val name: String,
    /** The worktree's own folder. */
    val path: String,
)

/**
 * letta-mobile-zxk2n.6 (G32, 0.34 worktrees): letta-code keeps the worktrees its agent creates in
 * `<repo>/.letta/worktrees/<name>` (its worktree watcher's `.letta/worktrees` directory) and moves
 * the conversation's cwd into a new one when the agent runs `git worktree add` there. The
 * protocol carries no worktree commands (no `git_op` through 0.34.10), so the client reads
 * worktrees from the path alone: [locate] for the "in worktree" banner and "back to the
 * repository", and [worktreesFolder] to list them with `list_in_directory`.
 */
object WorktreePaths {
    const val LETTA_DIR: String = ".letta"
    const val WORKTREES_DIR: String = "worktrees"

    /** The worktree [cwd] is in (it or one of its sub-folders), or null outside any. */
    fun locate(cwd: String?): WorktreeLocation? {
        if (cwd.isNullOrBlank()) return null
        val normalized = DirectoryPaths.normalize(cwd)
        val segments = normalized.split('/', '\\')
        val at = segments.windowed(2).indexOfLast { (a, b) -> a == LETTA_DIR && b == WORKTREES_DIR }
        if (at < 1 || at + 2 >= segments.size) return null
        val name = segments[at + 2].takeIf(String::isNotEmpty) ?: return null
        val separator = if (normalized.contains('\\') && !normalized.contains('/')) "\\" else "/"
        val root = segments.take(at).joinToString(separator).ifEmpty { separator }
        val path = segments.take(at + 3).joinToString(separator)
        return WorktreeLocation(root = DirectoryPaths.normalize(rootFix(root, separator)), name = name, path = path)
    }

    /** `<repo>/.letta/worktrees`, where letta-code puts the worktrees of the repository at [repo]. */
    fun worktreesFolder(repo: String): String =
        DirectoryPaths.child(DirectoryPaths.child(repo, LETTA_DIR), WORKTREES_DIR)

    /** The repository a worktree list belongs to: the worktree's root, or [cwd] itself outside one. */
    fun repositoryOf(cwd: String): String = locate(cwd)?.root ?: DirectoryPaths.normalize(cwd)

    // `C:` alone (a drive split off its separator) is the drive's root.
    private fun rootFix(root: String, separator: String): String =
        if (root.length == 2 && root[1] == ':') root + separator else root
}
