package com.letta.mobile.data.chat.tools

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.chat.tools.shell.ShellCommandLine
import com.letta.mobile.data.chat.tools.shell.ShellTools

/**
 * Which commands a command presenter claims: its [program] (ignoring case) and, optionally, the
 * [subcommands] its arguments start with. More subcommands is more specific: `meridian canvas`
 * beats `meridian`.
 */
@Immutable
data class CommandMatcher(val program: String, val subcommands: List<String> = emptyList()) {
    fun specificity(command: ShellCommandLine): Int? {
        if (!command.program.equals(program, ignoreCase = true)) return null
        if (command.args.take(subcommands.size) != subcommands) return null
        return subcommands.size
    }
}

/**
 * The second level of the presenter plugin contract: how one CLI's commands read when a shell
 * tool runs them (the canvas CLI's `meridian canvas compose`, a deploy tool…). The shell tool's
 * presenter asks the [CommandPresenterRegistry] for the command it runs, and falls back to its own
 * generic line when no command presenter claims it.
 */
interface CommandPresenter {
    /** Unique within its owner, like a [ToolPresenter.id]. */
    val id: String

    val matcher: CommandMatcher

    fun line(call: ToolCallFacts, command: ShellCommandLine): ToolStepLine

    fun inset(call: ToolCallFacts, command: ShellCommandLine): ToolInset?
}

/** A command presenter and the command of the script it claimed. */
@Immutable
class CommandMatch(val presenter: CommandPresenter, val command: ShellCommandLine)

/** The command presenters shell scripts are read with; bundled and contributed, ranked like [ToolPresenterRegistry]. */
@Immutable
class CommandPresenterRegistry private constructor(private val entries: List<RankedPresenter<CommandPresenter>>) {
    private val programs: Set<String> = entries.mapTo(mutableSetOf()) { it.presenter.matcher.program.lowercase() }

    /**
     * The first command of [script] a presenter claims. A script that names no registered program
     * is rejected by a substring check before it is parsed, so most shell calls cost nothing here.
     */
    fun find(script: String): CommandMatch? {
        if (programs.none { script.contains(it, ignoreCase = true) }) return null
        for (command in ShellTools.commandsOf(script)) {
            val presenter = entries.strongestClaim { it.matcher.specificity(command) } ?: continue
            return CommandMatch(presenter, command)
        }
        return null
    }

    fun register(presenter: CommandPresenter): CommandPresenterRegistry =
        CommandPresenterRegistry(entries.replacing(RankedPresenter(presenter, PresenterTier.Bundled)) { it.id })

    /** These presenters with [owner]'s manifest command presenters, each `<owner>/<name>`. */
    fun contribute(owner: String, specs: Map<String, CommandPresenterSpec>): CommandPresenterRegistry =
        specs.entries.fold(this) { registry, (name, spec) ->
            val entry = RankedPresenter<CommandPresenter>(SpecCommandPresenter("$owner/$name", spec), PresenterTier.Contributed)
            CommandPresenterRegistry(registry.entries.replacing(entry) { it.id })
        }

    val ids: List<String> get() = entries.map { it.presenter.id }

    companion object {
        val Empty: CommandPresenterRegistry = CommandPresenterRegistry(emptyList())
    }
}

/**
 * A shell call's inset: `dir $ command`, then its output, which reads as stderr when the command
 * exited non-zero. The output is the call's result as it is, never copied.
 */
fun shellCommandInset(call: ToolCallFacts): ToolInset? {
    val script = call.arguments?.let(ShellTools::scriptOf).orEmpty()
    val result = call.result?.takeIf { it.isNotBlank() }
    if (script.isEmpty() && result == null) return null
    val kind = if (ToolArguments.exitFailure(result) != null) ToolOutputStream.Kind.Stderr else ToolOutputStream.Kind.Output
    return ToolInset.Command(
        workingDirectory = ShellTools.WORKDIR_KEYS.firstNotNullOfOrNull(call::string),
        command = script,
        output = listOfNotNull(result?.let { ToolOutputStream(kind, it) }),
    )
}
