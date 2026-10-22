package com.letta.mobile.data.chat.tools

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a tool step's one muted line leads with: "Ran", "Read", "Edited"… A tool no presenter
 * knows reads as [Called] followed by its own name.
 */
@Serializable
enum class ToolVerb {
    @SerialName("ran")
    Ran,

    @SerialName("read")
    Read,

    @SerialName("wrote")
    Wrote,

    @SerialName("edited")
    Edited,

    @SerialName("searched")
    Searched,

    @SerialName("fetched")
    Fetched,

    @SerialName("delegated")
    Delegated,

    @SerialName("called")
    Called,
}

/**
 * A tool step's one line, whatever host draws it: the [verb], the optional [subject] after it, the
 * muted [target], what its chips open in the preview, and a failure the line should say. It is
 * cheap to build (no output is read past an exit-code scan), so every visible step has one; the
 * [ToolInset] is built only once the step is opened.
 */
@Immutable
data class ToolStepLine(
    val verb: ToolVerb,
    /** Named after the verb when the verb alone does not say what ran (a [ToolVerb.Called] tool's name). */
    val subject: String? = null,
    /** The one-line target: the command, the file, the query. Blank when there is none. */
    val target: String = "",
    val previews: List<ToolPreviewTarget> = emptyList(),
    val failure: ToolFailure? = null,
    /** Which presenter drew this; a contributed one is `<pluginId>/<presenter>`. */
    val presenterId: String,
)

/** What an opened step shows, in one of the shapes every host knows how to draw. */
@Immutable
sealed interface ToolInset {
    /** A shell command: `dir $ command`, then its output streams in order. */
    data class Command(
        val workingDirectory: String?,
        val command: String,
        val output: List<ToolOutputStream> = emptyList(),
    ) : ToolInset

    /** A unified diff (a file edit, a patch). */
    data class Diff(val unified: String) : ToolInset

    /** A replacement: the text before and after it. */
    data class BeforeAfter(val before: String, val after: String) : ToolInset

    /** Plain monospace text: a file read, search hits, a fetched page. */
    data class Text(val text: String) : ToolInset

    /** Any other call: its arguments as `key: value` lines, then its result. */
    data class Arguments(val fields: List<ToolArgumentField>, val result: String?) : ToolInset
}

/** One stretch of a command's output; stderr reads muted red. */
@Immutable
data class ToolOutputStream(val kind: Kind, val text: String) {
    enum class Kind { Output, Stderr }
}

/** One argument of a call, flattened for reading. */
@Immutable
data class ToolArgumentField(val key: String, val value: String)

/**
 * What a step's chip opens in the host's preview (the workspace sidebar on a wide window, the bottom
 * card on a phone). Each presenter declares its own; the renderer only routes them.
 */
@Immutable
sealed interface ToolPreviewTarget {
    /** A working-directory file, with the [lines] (1-based, inclusive) the call touched. */
    data class File(val path: String, val lines: IntRange? = null) : ToolPreviewTarget

    /** An entry of the agent's memory (a block label or a MemFS path), opened in the Memory view. */
    data class Memory(val path: String) : ToolPreviewTarget

    /** A subagent the call dispatched, opened in the dual view. */
    data class Subagent(val agentId: String) : ToolPreviewTarget

    /** A background task's output. */
    data class TaskOutput(val taskId: String) : ToolPreviewTarget
}

/** A failure the step line says out loud; success is quiet. [exitCode] is non-zero when known. */
@Immutable
data class ToolFailure(val exitCode: Int?)

/**
 * What a kind of step adds up to when several are grouped ("Explored files, ran commands"):
 * reading and searching are both exploring, writing and editing both edit files.
 */
enum class ToolActivity {
    Explored,
    Ran,
    Edited,
    Fetched,
    Delegated,
    Called,
}

/** The activity a step of this verb adds to its group. */
val ToolVerb.activity: ToolActivity
    get() = when (this) {
        ToolVerb.Read, ToolVerb.Searched -> ToolActivity.Explored
        ToolVerb.Ran -> ToolActivity.Ran
        ToolVerb.Wrote, ToolVerb.Edited -> ToolActivity.Edited
        ToolVerb.Fetched -> ToolActivity.Fetched
        ToolVerb.Delegated -> ToolActivity.Delegated
        ToolVerb.Called -> ToolActivity.Called
    }
