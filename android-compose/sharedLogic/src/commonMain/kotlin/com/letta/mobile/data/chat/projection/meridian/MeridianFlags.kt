package com.letta.mobile.data.chat.projection.meridian

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.jvm.JvmInline

/**
 * The words after a command's path, read as the host router reads them (letta-mobile-jna0o.6,
 * mirroring `MeridianInputBuilder`): a word starting `--` is a flag (`--name=value`, a bare boolean
 * `--name` when the schema says boolean, else `--name value`); anything else is a positional, which
 * fills [MeridianCommandSpec.positional] in order. `--input-file` names a file, not a field.
 * Values are typed by the tool's input schema, so `--limit 5` is `limit: 5`.
 */
internal class MeridianFlags(private val spec: MeridianCommandSpec, private val words: List<String>) {
    /** The input fields the flags and positionals set, in the order written. */
    val fields = LinkedHashMap<String, JsonElement>()
    private var positionals = 0
    private var index = 0

    init {
        while (index < words.size) next()
    }

    private fun next() {
        val word = words[index++]
        if (!Flag.isFlag(word)) {
            spec.positional.getOrNull(positionals++)?.let { set(it, word) }
            return
        }
        val flag = Flag(word)
        if (flag.name == INPUT_FILE) {
            valueAfter()
            return
        }
        val property = spec.property(flag.name)
        val value = flag.inline ?: valueFor(property)
        set(property, value)
    }

    private fun valueFor(property: String): String =
        if (spec.typeOf(property) == BOOLEAN) TRUE else valueAfter() ?: TRUE

    /** The next word as a flag's value; never another flag. */
    private fun valueAfter(): String? =
        words.getOrNull(index)?.takeUnless { Flag.isFlag(it) }?.also { index++ }

    private fun set(property: String, raw: String) {
        fields[property] = typed(spec.typeOf(property), raw)
    }

    private companion object {
        const val INPUT_FILE = "input-file"
        const val BOOLEAN = "boolean"
        const val TRUE = "true"

        /** [raw] as [type] says; a value that is not that type stays the text written. */
        fun typed(type: String?, raw: String): JsonElement = when (type) {
            "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive)
            "number" -> raw.toDoubleOrNull()?.let(::JsonPrimitive)
            BOOLEAN -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
            else -> null
        } ?: JsonPrimitive(raw)
    }
}

/** One `--name=value` / `--name` word. */
@JvmInline
private value class Flag(private val word: String) {
    private val body: String get() = word.removePrefix("--")

    val name: String get() = body.substringBefore('=')

    val inline: String? get() = body.takeIf { '=' in it }?.substringAfter('=')

    companion object {
        fun isFlag(word: String): Boolean = word.startsWith("--") && word != "--"
    }
}
