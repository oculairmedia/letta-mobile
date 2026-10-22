package com.letta.mobile.data.chat.tools

/** A [CommandPresenterSpec] as a [CommandPresenter]: what a plugin's manifest contributes. */
class SpecCommandPresenter(override val id: String, private val spec: CommandPresenterSpec) : CommandPresenter {
    override val matcher: CommandMatcher = CommandMatcher(spec.program, spec.subcommands)

    override fun line(call: ToolCallFacts, command: ShellCommandLine): ToolStepLine = ToolStepLine(
        verb = spec.verb,
        target = ToolArguments.firstLine(command.words.asShellScript()),
        previews = listOfNotNull(spec.preview?.let { preview -> command.flag(preview.flag)?.let { preview.kind.target(it) } }),
        failure = ToolArguments.exitFailure(call.result),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, command: ShellCommandLine): ToolInset? = ShellTools.commandInset(call)
}
