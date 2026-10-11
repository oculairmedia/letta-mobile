package com.letta.mobile.data.dreaming

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.memory.memfs.MemfsCommit
import com.letta.mobile.data.memory.memfs.MemfsCommitDiff
import com.letta.mobile.data.memory.memfs.MemfsCommitRef
import com.letta.mobile.data.memory.memfs.MemfsFileDiff
import com.letta.mobile.data.memory.memfs.MemfsHistoryScope
import com.letta.mobile.data.memory.memfs.MemfsSource

/** Whether a dream commit is a dream's own work or the merge that brought it into memory. */
enum class DreamCommitKind {
    /** The reflection run's commit (`<type>(reflection): <summary>`). */
    Change,

    /** `merge(reflection): <summary>`: an automatic merge, or the agent's reviewed integration. */
    Merge,
}

/**
 * letta-mobile-iczm7: one dream in the agent's memory history. letta-code has no dream log of its
 * own: every dream that changed memory left a commit in the MemFS repository, and that commit is
 * the record (what it was about, when, and through its diff which files it touched).
 */
@Immutable
data class DreamRun(
    val sha: String,
    /** The commit subject without its `(reflection):` prefix and marker emoji. */
    val summary: String,
    /** ISO-8601, as git prints it. */
    val timestamp: String,
    val kind: DreamCommitKind,
    val author: String?,
) {
    val shortSha: String get() = sha.take(SHORT_SHA_LENGTH)

    private companion object {
        const val SHORT_SHA_LENGTH = 7
    }
}

/** A dream with the files its commit touched. */
@Immutable
data class DreamRunDetail(val run: DreamRun, val files: List<MemfsFileDiff>) {
    val added: Int get() = files.sumOf { it.added }
    val removed: Int get() = files.sumOf { it.removed }
}

/**
 * Tells dream commits from the rest of the memory log the way letta-code's own memory viewer does
 * (`/\(reflection\)|🔮|reflection:/i`), and strips the markers for display.
 */
object DreamCommits {
    private val marker = Regex("""\(reflection\)|🔮|reflection:""", RegexOption.IGNORE_CASE)
    private val conventionalPrefix = Regex("""^\s*([a-z]+)\(reflection\)\s*:\s*""", RegexOption.IGNORE_CASE)
    private const val CRYSTAL_BALL = "🔮"
    private const val MERGE_TYPE = "merge"

    fun isDream(commit: MemfsCommit): Boolean = marker.containsMatchIn(commit.message)

    fun toRun(commit: MemfsCommit): DreamRun {
        val subject = commit.message.lineSequence().firstOrNull().orEmpty()
        val type = conventionalPrefix.find(subject)?.groupValues?.get(1)?.lowercase()
        val summary = subject.replace(conventionalPrefix, "").replace(CRYSTAL_BALL, "").trim()
        return DreamRun(
            sha = commit.sha,
            summary = summary.ifEmpty { subject.trim() },
            timestamp = commit.timestamp,
            kind = if (type == MERGE_TYPE) DreamCommitKind.Merge else DreamCommitKind.Change,
            author = commit.author,
        )
    }

    /** The dreams in [commits], newest first as the log lists them. */
    fun runs(commits: List<MemfsCommit>): List<DreamRun> = commits.filter(::isDream).map(::toRun)
}

/**
 * letta-mobile-iczm7: reads dream history from the agent's MemFS log ([MemfsSource], which works
 * over a direct App Server and over the Iroh workspace relay alike).
 */
class DreamHistorySource(private val memfs: MemfsSource) {
    suspend fun runs(agentId: String): List<DreamRun> =
        DreamCommits.runs(memfs.history(MemfsHistoryScope(agentId, path = null)))

    suspend fun detail(agentId: String, run: DreamRun): DreamRunDetail =
        DreamRunDetail(run, MemfsCommitDiff.parse(memfs.commitDiff(MemfsCommitRef(agentId, run.sha))))
}
