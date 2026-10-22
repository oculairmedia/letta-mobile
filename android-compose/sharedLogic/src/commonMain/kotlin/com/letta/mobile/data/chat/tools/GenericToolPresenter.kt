package com.letta.mobile.data.chat.tools

/**
 * The presenter of last resort, for any tool nothing else claims: "Called <name>" over its
 * preferred argument, opening to its arguments as `key: value` lines followed by its result. A file
 * the call names still opens in the preview.
 */
object GenericToolPresenter : ToolPresenter {
    override val id: String = "generic"

    override val matcher: ToolMatcher = ToolMatcher(listOf("*"))

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine = ToolStepLine(
        verb = ToolVerb.Called,
        subject = call.name,
        target = ToolArguments.target(call),
        previews = listOfNotNull(ToolArguments.pathPreview(call)),
        failure = ToolArguments.exitFailure(call.result),
        presenterId = id,
    )

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? = argumentsInset(call)

    /** [call]'s arguments as `key: value` lines (its raw text when not an object), then its result. */
    fun argumentsInset(call: ToolCallFacts): ToolInset? {
        val fields = call.arguments?.let(ToolArguments::fields)
            ?: listOfNotNull(call.rawArguments.takeIf { it.isNotBlank() }?.let { ToolArgumentField(RAW_ARGUMENTS, it) })
        val result = call.result?.takeIf { it.isNotBlank() }
        if (fields.isEmpty() && result == null) return null
        return ToolInset.Arguments(fields, result)
    }

    /** The key a call's arguments read under when they are not a JSON object. */
    const val RAW_ARGUMENTS: String = "arguments"
}
