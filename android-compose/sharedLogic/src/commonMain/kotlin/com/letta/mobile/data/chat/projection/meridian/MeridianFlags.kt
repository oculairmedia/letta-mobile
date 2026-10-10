package com.letta.mobile.data.chat.projection.meridian

import com.letta.mobile.data.canvas.CanvasToolContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmInline

/**
 * The words after `meridian canvas <verb>` (letta-mobile-jna0o.6): the native argument fields the
 * flags name (`--canvas`, `--dry-run`, `--cursor`, `--limit`, `--title`), an inline `--input`, and
 * the positional words. A value flag takes `--name=value` or the next word.
 */
internal class MeridianFlags(private val words: List<String>) {
    val fields = LinkedHashMap<String, JsonElement>()
    private val positionals = mutableListOf<String>()
    var input: String? = null
        private set
    private var index = 0

    init {
        while (index < words.size) next()
    }

    /** The native tool [verb] runs (design doc, "CLI surface"); null for help-like or unknown verbs. */
    fun nativeToolFor(verb: String): String? = when (verb) {
        "guide" -> CanvasToolContract.COMPOSE_GUIDE.takeIf { positionals.firstOrNull() in GUIDE_TOPICS }
        else -> VERB_TOOLS[verb]
    }

    private fun next() {
        val word = words[index++]
        if (!Flag.isFlag(word)) {
            positionals += word
            return
        }
        val flag = Flag(word)
        val value = flag.inline ?: valueAfter(flag)
        when (val name = flag.name) {
            in BOOLEAN_FLAGS -> fields[BOOLEAN_FLAGS.getValue(name)] = JsonPrimitive(flag.inline?.toBooleanStrictOrNull() ?: true)
            in INPUT_FLAGS -> input = value
            in VALUE_FLAGS -> value?.let { fields[VALUE_FLAGS.getValue(name)] = flag.field(it) }
        }
    }

    private fun valueAfter(flag: Flag): String? =
        if (flag.name in TAKES_VALUE) words.getOrNull(index)?.also { index++ } else null
}

/** One `--name=value` / `--name` word. */
@JvmInline
private value class Flag(private val word: String) {
    private val body: String get() = word.trimStart('-')

    val name: String get() = body.substringBefore('=')

    val inline: String? get() = body.takeIf { '=' in it }?.substringAfter('=')

    /** [value] as this flag's JSON field: `--limit` is a number. */
    fun field(value: String): JsonElement =
        if (name == "limit") value.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value) else JsonPrimitive(value)

    companion object {
        fun isFlag(word: String): Boolean = word.startsWith("-") && word != "-"
    }
}

/** `meridian canvas guide` alone, or `guide compose`. */
private val GUIDE_TOPICS = setOf(null, "compose")

/** The CLI verbs (design doc, "CLI surface") and the native tools they run. */
private val VERB_TOOLS = mapOf(
    "compose" to CanvasToolContract.COMPOSE,
    "layout" to CanvasToolContract.GET_LAYOUT,
    "get-layout" to CanvasToolContract.GET_LAYOUT,
    "scene" to CanvasToolContract.GET_SCENE,
    "get-scene" to CanvasToolContract.GET_SCENE,
    "apply-ops" to CanvasToolContract.APPLY_OPS,
    "replace-scene" to CanvasToolContract.REPLACE_SCENE,
    "list" to CanvasToolContract.LIST,
    "create" to CanvasToolContract.CREATE,
    "preview" to CanvasToolContract.RENDER_PREVIEW,
    "render-preview" to CanvasToolContract.RENDER_PREVIEW,
    "export-svg" to CanvasToolContract.EXPORT_SVG,
)

private val BOOLEAN_FLAGS = mapOf("dry-run" to "dry_run", "dry_run" to "dry_run")
private val VALUE_FLAGS = mapOf(
    "canvas" to "canvas_id", "canvas-id" to "canvas_id", "canvas_id" to "canvas_id",
    "cursor" to "cursor", "limit" to "limit", "title" to "title",
)
private val INPUT_FLAGS = setOf("input", "json")

/** Input files (`--input-file x.json`): the input is not in the script, but the flag takes the next word. */
private val FILE_FLAGS = setOf("input-file", "file", "f")
private val TAKES_VALUE = VALUE_FLAGS.keys + INPUT_FLAGS + FILE_FLAGS
