package com.letta.mobile.data.plugin

import com.letta.mobile.data.chat.tools.ToolInsetKind
import com.letta.mobile.data.chat.tools.ToolInsetSpec
import com.letta.mobile.data.schema.SchemaProblem

/** What the schema cannot say about a manifest's `toolPresenters`: each inset names the arguments its kind reads. */
internal object PluginToolPresenterRules {
    fun check(manifest: PluginManifest): List<SchemaProblem> = manifest.toolPresenters.flatMap { (name, spec) ->
        val at = ManifestPointer.ROOT / "toolPresenters" / name
        listOfNotNull(spec.inset?.let { missingArgument(it, at / "inset") }) + duplicates(spec.tools, at / "tools")
    }

    private fun missingArgument(inset: ToolInsetSpec, at: ManifestPointer): SchemaProblem? = when (inset.kind) {
        ToolInsetKind.Command -> bad(at / "command", "a command inset names the argument holding the command")
            .takeIf { inset.command == null }
        ToolInsetKind.BeforeAfter -> bad(at / "before", "a before_after inset names its before or after argument")
            .takeIf { inset.before == null && inset.after == null }
        ToolInsetKind.Diff, ToolInsetKind.Text, ToolInsetKind.Arguments -> null
    }

    private fun bad(at: ManifestPointer, message: String) = PluginManifestProblem.BAD_TOOL_PRESENTER.at(at, message)
}
