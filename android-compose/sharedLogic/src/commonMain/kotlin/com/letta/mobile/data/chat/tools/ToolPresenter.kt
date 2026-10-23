package com.letta.mobile.data.chat.tools

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One tool call as a presenter reads it: its name, its arguments (parsed once; null when they are
 * not a JSON object, still streaming or malformed), its result so far, and whether it [failed] (its
 * result is then what went wrong, not what it produced).
 */
@Immutable
class ToolCallFacts(val name: String, val rawArguments: String, val result: String?, val failed: Boolean = false) {
    /** Parsed on first read, so a step whose presenter never looks at its arguments never parses them. */
    val arguments: JsonObject? get() = parsed.value

    private val parsed = lazy(LazyThreadSafetyMode.NONE) { parseArguments(rawArguments) }

    /** Whether anything has read [arguments] yet. */
    internal val argumentsParsed: Boolean get() = parsed.isInitialized()

    /** The string argument [key], trimmed, or null when absent, blank or not a string. */
    fun string(key: String): String? =
        (arguments?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** The integer argument [key], whether written as a number or a numeric string. */
    fun int(key: String): Int? {
        val value = arguments?.get(key) as? JsonPrimitive ?: return null
        return value.intOrNull ?: value.content.toIntOrNull()
    }

    private companion object {
        fun parseArguments(raw: String): JsonObject? {
            if (raw.isBlank()) return null
            return runCatching { LettaJson.wire.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        }
    }
}

/**
 * Which tool calls a presenter claims: tool names (exact, or a glob with `*`, matched ignoring case)
 * and, optionally, arguments a call must carry (its arg shape). A more literal pattern is more
 * specific: `memory_replace` beats `memory_*`, which beats `*`.
 */
@Immutable
data class ToolMatcher(val names: List<String>, val requiredArgs: Set<String> = emptySet()) {
    private val patterns: List<NamePattern> = names.map(::NamePattern)

    /** How specifically this claims [call], or null when it does not. */
    fun specificity(call: ToolCallFacts): Int? {
        if (requiredArgs.isNotEmpty() && requiredArgs.any { call.arguments?.containsKey(it) != true }) return null
        val best = patterns.filter { it.matches(call.name) }.maxOfOrNull { it.specificity } ?: return null
        return best + requiredArgs.size
    }

    private class NamePattern(glob: String) {
        private val exact = '*' !in glob
        private val regex = Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

        /** An exact name outranks any glob; among globs, more literal characters win. */
        val specificity: Int = if (exact) EXACT_NAME else glob.count { it != '*' } * GLOB_CHAR

        fun matches(name: String): Boolean = regex.matches(name)
    }

    private companion object {
        const val EXACT_NAME = 100_000
        const val GLOB_CHAR = 10
    }
}

/**
 * The presenter plugin contract: one unit per tool (or tool family) that says how its calls read.
 * Bundled presenters and plugin-contributed ones register into the same [ToolPresenterRegistry];
 * the renderer only draws a [ToolStepLine] and a [ToolInset] and never names a tool.
 *
 * [line] runs for every visible step and must stay cheap: read arguments, never scan a whole
 * output. [inset] runs only once the step is opened, and may refer to the call's result text
 * without copying it.
 */
interface ToolPresenter {
    /** Unique within its owner: `bash`, or `<pluginId>/<presenter>` for a contributed one. */
    val id: String

    val matcher: ToolMatcher

    fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine

    fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset?
}

/** What a presenter may consult besides its call: the command presenters a shell tool hands its commands to. */
@Immutable
class ToolPresenterScope(val commands: CommandPresenterRegistry)
