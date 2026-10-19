package com.letta.mobile.data.workspace.directory

import com.letta.mobile.data.workspace.WorkspacePaths
import kotlin.jvm.JvmInline

/**
 * A folder path as a device reports it, POSIX or Windows, independent of the platform this runs
 * on (the device may be another machine). Folder-path arithmetic for the working-directory picker.
 */
@JvmInline
value class DevicePath(val value: String) {
    /** Trimmed, without a trailing separator unless it is a root (`/`, `C:\`). */
    val normalized: DevicePath
        get() {
            val trimmed = value.trim()
            return DevicePath(
                when {
                    trimmed in POSIX_ROOTS -> trimmed
                    WINDOWS_DRIVE_ROOT.matches(trimmed) -> trimmed.take(2) + separatorOf(trimmed)
                    else -> trimmed.trimEnd('/', '\\').ifEmpty { trimmed.take(1) }
                },
            )
        }

    val isAbsolute: Boolean get() = WorkspacePaths.isAbsolute(value.trim())

    /** At a file-system root, which has no parent. */
    val isRoot: Boolean
        get() {
            val path = normalized.value
            return path in POSIX_ROOTS || WINDOWS_DRIVE_ROOT.matches(path)
        }

    /** The folder containing this one, or null at a root. */
    val parent: DevicePath?
        get() {
            if (isRoot) return null
            val path = normalized.value
            val cut = path.lastIndexOfAny(SEPARATORS)
            return when {
                cut < 0 -> null
                cut == 0 -> DevicePath(path.take(1))
                // `C:\Users` -> `C:\`
                cut == 2 && path[1] == ':' -> DevicePath(path.take(3))
                else -> DevicePath(path.substring(0, cut))
            }
        }

    /** The last segment; the path itself at a root. */
    val name: String
        get() {
            val path = normalized.value
            return if (isRoot) path else path.substringAfterLast('/').substringAfterLast('\\')
        }

    /** [child] inside this folder, with this path's own separator. */
    fun child(child: FolderName): DevicePath {
        val base = normalized.value
        val joined = if (base.endsWith('/') || base.endsWith('\\')) base + child.value else base + separatorOf(base) + child.value
        return DevicePath(joined)
    }

    /** The same folder, ignoring a trailing separator. */
    fun sameAs(other: DevicePath?): Boolean = other != null && normalized.value == other.normalized.value

    private companion object {
        val WINDOWS_DRIVE_ROOT = Regex("^[A-Za-z]:[\\\\/]?$")
        val POSIX_ROOTS = setOf("/", "\\")
        val SEPARATORS = charArrayOf('/', '\\')

        fun separatorOf(path: String): Char = if (path.contains('\\') && !path.contains('/')) '\\' else '/'
    }
}

/** One path segment: a folder's own name. */
@JvmInline
value class FolderName(val value: String) {
    /** A name the picker may create: one segment, not `.`/`..`, no separator or NUL. */
    val isValid: Boolean
        get() {
            val trimmed = value.trim()
            if (trimmed in RESERVED_NAMES) return false
            return trimmed.none { it in FORBIDDEN_CHARS }
        }

    private companion object {
        val RESERVED_NAMES = setOf("", ".", "..")
        val FORBIDDEN_CHARS = setOf('/', '\\', '\u0000')
    }
}

/** [path]'s folder, for callers holding a plain string; null for a null or blank one. */
fun devicePathOrNull(path: String?): DevicePath? = path?.takeIf { it.isNotBlank() }?.let(::DevicePath)

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
 *  (G32, 0.34 worktrees): letta-code keeps the worktrees its agent creates in
 * `<repo>/.letta/worktrees/<name>` (its worktree watcher's `.letta/worktrees` directory) and moves
 * the conversation's cwd into a new one when the agent adds a worktree there. The protocol carries
 * no worktree commands (none through 0.34.10), so the client reads worktrees from the path alone:
 * [locate] for the "in worktree" banner and "back to the repository", and [worktreesFolder] to
 * list them with `list_in_directory`.
 */
object WorktreePaths {
    const val LETTA_DIR: String = ".letta"
    const val WORKTREES_DIR: String = "worktrees"

    /** The worktree [cwd] is in (it or one of its sub-folders), or null outside any. */
    fun locate(cwd: DevicePath?): WorktreeLocation? {
        val normalized = cwd?.normalized?.value?.takeIf { it.isNotEmpty() } ?: return null
        val segments = normalized.split('/', '\\')
        val at = segments.windowed(2).indexOfLast { (a, b) -> a == LETTA_DIR && b == WORKTREES_DIR }
        if (at < 1 || at + 2 >= segments.size) return null
        val name = segments[at + 2].takeIf(String::isNotEmpty) ?: return null
        val separator = if (normalized.contains('\\') && !normalized.contains('/')) "\\" else "/"
        val root = segments.take(at).joinToString(separator).ifEmpty { separator }.asDriveRoot()
        val path = segments.take(at + 3).joinToString(separator)
        return WorktreeLocation(root = DevicePath(root).normalized.value, name = name, path = path)
    }

    /** `<repo>/.letta/worktrees`, where letta-code puts the worktrees of the repository at [repo]. */
    fun worktreesFolder(repo: DevicePath): DevicePath = repo.child(FolderName(LETTA_DIR)).child(FolderName(WORKTREES_DIR))

    /** The repository a worktree list belongs to: the worktree's root, or [cwd] itself outside one. */
    fun repositoryOf(cwd: DevicePath): DevicePath = locate(cwd)?.root?.let(::DevicePath) ?: cwd.normalized

    // `C:` alone (a drive split off its separator) is that drive's root.
    private fun String.asDriveRoot(): String = if (length == 2 && this[1] == ':') "$this\\" else this
}
