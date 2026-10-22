package com.letta.mobile.data.chat.tools

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A presenter a plugin contributes in its manifest (`toolPresenters`), with no code: which tools
 * it claims, the verb its line leads with, which argument is the line's target, what its inset
 * shows and what its chip opens. Every field names arguments of the call; an inset part with no
 * argument named reads the call's result.
 */
@Immutable
@Serializable
data class ToolPresenterSpec(
    /** Tool names, exact or globbed with `*`, matched ignoring case. */
    val tools: List<String>,
    /** Arguments a call must carry to be claimed. */
    val requiredArgs: List<String> = emptyList(),
    val verb: ToolVerb = ToolVerb.Called,
    /** The argument the line shows after the verb. */
    val target: String? = null,
    val inset: ToolInsetSpec? = null,
    val preview: ToolPreviewSpec? = null,
)

/** Which [ToolInset] a spec draws, and from which arguments. */
@Immutable
@Serializable
data class ToolInsetSpec(
    val kind: ToolInsetKind,
    /** [ToolInsetKind.Command]: the command argument and the working-directory argument. */
    val command: String? = null,
    val cwd: String? = null,
    /** [ToolInsetKind.BeforeAfter]: the replaced and the replacing text. */
    val before: String? = null,
    val after: String? = null,
    /** [ToolInsetKind.Text] / [ToolInsetKind.Diff]: the argument holding it; the result when null. */
    val text: String? = null,
)

@Serializable
enum class ToolInsetKind {
    @SerialName("command")
    Command,

    @SerialName("diff")
    Diff,

    @SerialName("before_after")
    BeforeAfter,

    @SerialName("text")
    Text,

    @SerialName("arguments")
    Arguments,
}

/** What a spec's chip opens: [arg] holds the path, block, agent or task id. */
@Immutable
@Serializable
data class ToolPreviewSpec(
    val kind: ToolPreviewKind,
    val arg: String,
    /** [ToolPreviewKind.File]: the arguments holding the first line (1-based) and how many lines. */
    val startLine: String? = null,
    val lineCount: String? = null,
)

@Serializable
enum class ToolPreviewKind {
    @SerialName("file")
    File,

    @SerialName("memory")
    Memory,

    @SerialName("subagent")
    Subagent,

    @SerialName("task")
    Task,
}

/**
 * A command presenter a plugin contributes in its manifest (`commandPresenters`): the CLI command
 * it claims when a shell tool runs it, the verb its line leads with, and what its chip opens.
 */
@Immutable
@Serializable
data class CommandPresenterSpec(
    val program: String,
    val subcommands: List<String> = emptyList(),
    val verb: ToolVerb = ToolVerb.Ran,
    val preview: CommandPreviewSpec? = null,
)

/** What a command's chip opens: the value of its [flag] (`--id`), as a [kind] of preview. */
@Immutable
@Serializable
data class CommandPreviewSpec(val kind: ToolPreviewKind, val flag: String)

/** A preview of this kind for [value] (a path, block, agent or task id). */
fun ToolPreviewKind.target(value: String, lines: IntRange? = null): ToolPreviewTarget = when (this) {
    ToolPreviewKind.File -> ToolPreviewTarget.File(value, lines)
    ToolPreviewKind.Memory -> ToolPreviewTarget.Memory(value)
    ToolPreviewKind.Subagent -> ToolPreviewTarget.Subagent(value)
    ToolPreviewKind.Task -> ToolPreviewTarget.TaskOutput(value)
}
