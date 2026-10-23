package com.letta.mobile.data.chat.tools

import androidx.compose.runtime.Immutable

/**
 * The presenters tool calls are drawn with, each claiming calls through its [ToolMatcher], and the
 * [commands] a shell tool's presenter hands each command it runs to.
 *
 * Bundled presenters ([register]) and plugin-contributed ones ([contribute]) register the same
 * way; the [GenericToolPresenter] takes whatever none claims. A call goes to the most specific
 * claim, bundled before contributed, and among equals to the one registered last.
 */
@Immutable
class ToolPresenterRegistry private constructor(
    private val entries: List<RankedPresenter<ToolPresenter>>,
    val commands: CommandPresenterRegistry,
) {
    private val scope = ToolPresenterScope(commands)

    /** The presenter [call] is drawn with. */
    fun presenterFor(call: ToolCallFacts): ToolPresenter =
        entries.strongestClaim { it.matcher.specificity(call) } ?: GenericToolPresenter

    /** [call]'s step line: cheap, built for every visible step. */
    fun line(call: ToolCallFacts): ToolStepLine = presenterFor(call).line(call, scope)

    /** [call]'s inset: built only for an opened step. */
    fun inset(call: ToolCallFacts): ToolInset? = presenterFor(call).inset(call, scope)

    /** These presenters with [presenter] bundled; an id registered before is replaced. */
    fun register(presenter: ToolPresenter): ToolPresenterRegistry = with(RankedPresenter(presenter, PresenterTier.Bundled))

    /** These presenters with every one of [presenters] bundled, in order. */
    fun registerAll(presenters: List<ToolPresenter>): ToolPresenterRegistry =
        presenters.fold(this) { registry, presenter -> registry.register(presenter) }

    /** These presenters with every one of [commands] bundled for the commands shell tools run. */
    fun registerCommands(commands: List<CommandPresenter>): ToolPresenterRegistry =
        commands.fold(this) { registry, command -> registry.register(command) }

    /** These presenters with [command] bundled for the commands shell tools run. */
    fun register(command: CommandPresenter): ToolPresenterRegistry = ToolPresenterRegistry(entries, commands.register(command))

    /**
     * These presenters with what [owner]'s manifest declares: its tool presenters and its command
     * presenters, each registered as `<owner>/<name>`.
     */
    fun contribute(
        owner: String,
        tools: Map<String, ToolPresenterSpec>,
        commands: Map<String, CommandPresenterSpec> = emptyMap(),
    ): ToolPresenterRegistry {
        val withTools = tools.entries.fold(this) { registry, (name, spec) ->
            registry.with(RankedPresenter(SpecToolPresenter("$owner/$name", spec), PresenterTier.Contributed))
        }
        return ToolPresenterRegistry(withTools.entries, withTools.commands.contribute(owner, commands))
    }

    /** Every registered tool presenter's id, in registration order. */
    val ids: List<String> get() = entries.map { it.presenter.id }

    private fun with(entry: RankedPresenter<ToolPresenter>): ToolPresenterRegistry =
        ToolPresenterRegistry(entries.replacing(entry) { it.id }, commands)

    companion object {
        /** No presenters: every call reads through the [GenericToolPresenter]. */
        val Empty: ToolPresenterRegistry = ToolPresenterRegistry(emptyList(), CommandPresenterRegistry.Empty)
    }
}
