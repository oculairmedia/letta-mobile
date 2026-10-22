package com.letta.mobile.data.chat.tools

import androidx.compose.runtime.Immutable

/** One command a shell call runs, from its program on (`meridian canvas compose --id x`). */
@Immutable
data class ShellCommandLine(val words: List<String>, val stdin: String? = null) {
    /** The program's name without its directory: `meridian` of `/usr/local/bin/meridian`. */
    val program: String get() = words.firstOrNull()?.substringAfterLast('/').orEmpty()

    /** The words after the program. */
    val args: List<String> get() = words.drop(1)

    /** The word after [flag] (`--id x`), or the value of `--flag=x`; null when it is not given. */
    fun flag(flag: String): String? {
        val index = args.indexOf(flag)
        if (index >= 0) return args.getOrNull(index + 1)
        return args.firstOrNull { it.startsWith("$flag=") }?.substringAfter('=')
    }
}

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
