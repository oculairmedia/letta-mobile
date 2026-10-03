package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract
import com.letta.mobile.data.schema.JsonSchemaHooks
import com.letta.mobile.data.schema.JsonSchemaText
import com.letta.mobile.data.schema.SchemaNode
import com.letta.mobile.data.schema.SchemaProblem
import com.letta.mobile.data.schema.SchemaProblemCode
import kotlinx.serialization.json.JsonElement

/**
 * How `canvas_compose` words the generic check's problems ([com.letta.mobile.data.schema.JsonSchemaCheck]):
 * a pattern miss names what the field is (a colour, a key, an artifact id), a kind that exists but
 * cannot sit where it was put is a nesting problem, and the generic codes fold into
 * [ComposeProblemCode]s the model already knows.
 */
internal object ComposeSchemaHooks : JsonSchemaHooks {
    override fun badPattern(node: SchemaNode, text: String): SchemaProblem = when (node.field) {
        "color" -> problem(node.path, ComposeProblemCode.BAD_COLOR, "'$text' is not a colour; use ${Contract.COLOR_PRESETS.joinToString()} or #rrggbb")
        "artifact_id" -> problem(node.path, ComposeProblemCode.BAD_KEY, "'$text' is not an artifact id: lowercase letters, digits, _ and -, at most 48")
        else -> problem(node.path, ComposeProblemCode.BAD_KEY, "'$text' is not a key: lowercase letters, digits, _ and -, at most 32")
    }

    override fun unknownBranch(node: SchemaNode, discriminator: String, value: JsonElement, allowed: List<String>): SchemaProblem {
        val name = JsonSchemaText.stringContent(value)
        if (name != null && ComposeKind.entries.any { it.name == name }) {
            return problem(
                node.pointer(discriminator), ComposeProblemCode.NESTING_TOO_DEEP,
                "a $name cannot go here; groups nest ${Contract.MAX_GROUP_DEPTH} level deep and hold ${JsonSchemaText.orList(allowed)}",
            )
        }
        return problem(
            node.pointer(discriminator), ComposeProblemCode.UNKNOWN_KIND,
            "${JsonSchemaText.quoted(value)} is not a kind; use ${JsonSchemaText.orList(allowed)}",
        )
    }

    /** [problem] as `canvas_compose` reports it. */
    fun toCompose(problem: SchemaProblem): ComposeProblem = ComposeProblem(problem.path, composeCode(problem.code), problem.message)

    private fun composeCode(code: String): String = when (code) {
        SchemaProblemCode.TOO_FEW_ITEMS.name,
        SchemaProblemCode.TOO_SHORT.name,
        SchemaProblemCode.NOT_ALLOWED.name,
        SchemaProblemCode.OUT_OF_RANGE.name,
        SchemaProblemCode.BAD_PATTERN.name,
        SchemaProblemCode.NO_BRANCH.name,
        -> ComposeProblemCode.BAD_VALUE.name
        else -> code
    }

    private fun problem(path: String, code: ComposeProblemCode, message: String) = SchemaProblem(path, code.name, message)
}
