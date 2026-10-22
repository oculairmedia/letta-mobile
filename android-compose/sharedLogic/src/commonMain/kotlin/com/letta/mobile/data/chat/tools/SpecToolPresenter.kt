package com.letta.mobile.data.chat.tools

/** A [ToolPresenterSpec] as a [ToolPresenter]: what a plugin's manifest contributes. */
class SpecToolPresenter(override val id: String, private val spec: ToolPresenterSpec) : ToolPresenter {
    override val matcher: ToolMatcher = ToolMatcher(spec.tools, spec.requiredArgs.toSet())

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine = ToolStepLine(
        verb = spec.verb,
        subject = call.name.takeIf { spec.verb == ToolVerb.Called },
        target = spec.target?.let(call::string)?.let(ToolArguments::firstLine) ?: ToolArguments.target(call),
        previews = listOfNotNull(spec.preview?.let { previewOf(it, call) }),
        failure = ToolArguments.exitFailure(call.result),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        val inset = spec.inset ?: return GenericToolPresenter.argumentsInset(call)
        val text = inset.text?.let(call::string) ?: call.result?.takeIf { it.isNotBlank() }
        return when (inset.kind) {
            ToolInsetKind.Command -> commandInset(inset, call)
            ToolInsetKind.Diff -> text?.let(ToolInset::Diff)
            ToolInsetKind.Text -> text?.let(ToolInset::Text)
            ToolInsetKind.BeforeAfter -> beforeAfterInset(inset, call)
            ToolInsetKind.Arguments -> GenericToolPresenter.argumentsInset(call)
        }
    }

    private fun commandInset(inset: ToolInsetSpec, call: ToolCallFacts): ToolInset? {
        val command = inset.command?.let(call::string) ?: return null
        val output = listOfNotNull(call.result?.takeIf { it.isNotBlank() }?.let { ToolOutputStream(ToolOutputStream.Kind.Output, it) })
        return ToolInset.Command(inset.cwd?.let(call::string), command, output)
    }

    private fun beforeAfterInset(inset: ToolInsetSpec, call: ToolCallFacts): ToolInset? {
        val before = inset.before?.let(call::string).orEmpty()
        val after = inset.after?.let(call::string).orEmpty()
        if (before.isEmpty() && after.isEmpty()) return null
        return ToolInset.BeforeAfter(before, after)
    }

    private fun previewOf(preview: ToolPreviewSpec, call: ToolCallFacts): ToolPreviewTarget? {
        val value = call.string(preview.arg) ?: return null
        return preview.kind.target(value, linesOf(preview, call).takeIf { preview.kind == ToolPreviewKind.File })
    }

    private fun linesOf(preview: ToolPreviewSpec, call: ToolCallFacts): IntRange? {
        val start = preview.startLine?.let(call::int)?.coerceAtLeast(1) ?: return null
        val count = preview.lineCount?.let(call::int)?.coerceAtLeast(1) ?: 1
        return start until start + count
    }
}
