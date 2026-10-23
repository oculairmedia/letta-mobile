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

    /** A background task or process stopped: "Stopped". */
    @SerialName("stopped")
    Stopped,

    /** A background task's output followed while it runs: "Watching". */
    @SerialName("watching")
    Watching,

    /** A list or record brought up to date (the todo list): "Updated". */
    @SerialName("updated")
    Updated,

    /** A plan entered or proposed: "Planned". */
    @SerialName("planned")
    Planned,

    /** A skill or other capability put to use: "Used". */
    @SerialName("used")
    Used,

    /** Notes, checklists or cards put on a canvas: "Composed canvas". */
    @SerialName("composed_canvas")
    ComposedCanvas,

    /** A canvas's drawing changed (ops applied, the scene replaced): "Edited canvas". */
    @SerialName("edited_canvas")
    EditedCanvas,

    /** A canvas read, listed, previewed or exported: "Read canvas". */
    @SerialName("read_canvas")
    ReadCanvas,

    /** A new canvas: "Created canvas". */
    @SerialName("created_canvas")
    CreatedCanvas,

    /** A memory block's text replaced in place: "Edited memory". */
    @SerialName("edited_memory")
    EditedMemory,

    /** Text inserted into or appended to a memory block: "Added to memory". */
    @SerialName("added_to_memory")
    AddedToMemory,

    /** A memory block's whole value rewritten: "Rewrote memory". */
    @SerialName("rewrote_memory")
    RewroteMemory,

    /** A MemFS entry created, edited, renamed or deleted: "Wrote memfs". */
    @SerialName("wrote_memfs")
    WroteMemfs,
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
    /**
     * Whether the step opens at all: the presenter's cheap answer from the call's facts, given
     * before any inset is built. False for a step that is all line (a stopped task); a failed
     * step still opens to what went wrong.
     */
    val expandable: Boolean = true,
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

    /** A whole value rewritten (a memory block's rethink): the new [value], said to replace all of it. */
    data class Rewrite(val value: String) : ToolInset

    /**
     * A checklist (a todo list): its [items] in order, the one at [current] in progress (null when
     * none is). Done items read struck through and muted, the current one with a small spinner, the
     * rest as `○`.
     */
    data class Checklist(val items: List<Item>, val current: Int? = null) : ToolInset {
        /** One item: its [text], and whether it is [done]. */
        data class Item(val text: String, val done: Boolean)
    }

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

    /** A MemFS entry of the agent's memory (`system/human.md`), opened in the Memory view's Files. */
    data class Memory(val path: String) : ToolPreviewTarget

    /** A core memory block by its [label] (`persona`), opened focused in the Memory view's graph. */
    data class MemoryBlock(val label: String) : ToolPreviewTarget

    /** A subagent the call dispatched, opened in the dual view. */
    data class Subagent(val agentId: String) : ToolPreviewTarget

    /** A background task's output. */
    data class TaskOutput(val taskId: String) : ToolPreviewTarget

    /** The conversation's canvas, which the call drew on or read. */
    data object Canvas : ToolPreviewTarget
}

/** A failure the step line says out loud; success is quiet. [exitCode] is non-zero when known. */
@Immutable
data class ToolFailure(val exitCode: Int?)
