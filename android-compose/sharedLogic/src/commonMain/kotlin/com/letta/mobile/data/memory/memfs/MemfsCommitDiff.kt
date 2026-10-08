package com.letta.mobile.data.memory.memfs

import com.letta.mobile.data.diff.DiffLineKind
import com.letta.mobile.data.diff.UnifiedDiff

/**
 * Splits a memory commit's patch (`git show <sha> --format=`) into one [MemfsFileDiff] per file,
 * dropping git's per-file metadata so each section is plain unified-diff hunks for
 * [UnifiedDiff] to number and colour.
 */
object MemfsCommitDiff {
    private const val FILE_HEADER = "diff --git "

    fun parse(patch: String): List<MemfsFileDiff> =
        sections(patch).map(::fileDiff)

    private fun sections(patch: String): List<List<String>> {
        val sections = mutableListOf<MutableList<String>>()
        for (line in patch.split('\n')) {
            if (line.startsWith(FILE_HEADER)) {
                sections += mutableListOf(line)
            } else {
                sections.lastOrNull()?.add(line)
            }
        }
        return sections
    }

    private fun fileDiff(section: List<String>): MemfsFileDiff {
        val header = section.first()
        val body = section.drop(1)
        val hunkStart = body.indexOfFirst { it.startsWith("@@") }
        val metadata = if (hunkStart < 0) body else body.subList(0, hunkStart)
        val lines = if (hunkStart < 0) {
            emptyList()
        } else {
            UnifiedDiff.parse(body.subList(hunkStart, body.size).joinToString("\n").trimEnd('\n'))
                .filterNot { it.kind == DiffLineKind.FileHeader }
        }
        val (added, removed) = UnifiedDiff.stats(lines)
        return MemfsFileDiff(
            path = pathOf(header, metadata),
            change = changeOf(metadata),
            lines = lines,
            added = added,
            removed = removed,
        )
    }

    /** The new path from `+++ b/…`, else the old one from `--- a/…`, else the `diff --git` header. */
    private fun pathOf(header: String, metadata: List<String>): String {
        metadata.firstOrNull { it.startsWith("+++ b/") }?.let { return it.removePrefix("+++ b/") }
        metadata.firstOrNull { it.startsWith("--- a/") }?.let { return it.removePrefix("--- a/") }
        return header.removePrefix(FILE_HEADER).substringAfter(" b/", header.removePrefix("$FILE_HEADER a/"))
    }

    private fun changeOf(metadata: List<String>): MemfsFileChange = when {
        metadata.any { it.startsWith("Binary files") } -> MemfsFileChange.Binary
        metadata.any { it.startsWith("new file mode") } -> MemfsFileChange.Added
        metadata.any { it.startsWith("deleted file mode") } -> MemfsFileChange.Deleted
        else -> MemfsFileChange.Modified
    }
}
