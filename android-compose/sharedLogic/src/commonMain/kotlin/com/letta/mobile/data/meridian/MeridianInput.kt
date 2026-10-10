package com.letta.mobile.data.meridian

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `--flag` as written: its name, and its value (null for a bare boolean flag). */
internal data class MeridianFlag(val name: String, val value: String?)

/**
 * The words after a command: positionals, `--flag value` / `--flag=value` scalars and
 * `--input-file`, plus the [stdin] JSON text once the front door has resolved it.
 */
internal data class MeridianArgs(
    val positionals: List<String>,
    val flags: List<MeridianFlag>,
    val inputFile: String?,
    val stdin: String? = null,
)

/** One input property of a command's tool, as the tool's input schema describes it. */
internal class MeridianInputProperty(val name: String, schema: JsonObject?) {
    val type: String? = (schema?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val pointer: String get() = "/$name"
    val flag: String get() = "--${name.replace('_', '-')}"
    val isBoolean: Boolean get() = type == "boolean"

    fun expected(): String = when (type) {
        "array", "object" -> "given in the stdin JSON (an $type cannot be a flag)"
        "integer" -> "an integer"
        else -> "a $type"
    }
}

/** A scalar written in argv for [property]: a positional, or a flag's value. */
internal data class MeridianRawValue(val property: MeridianInputProperty, val raw: String) {
    /** [raw] typed as the schema says, or null when it is not that type (or cannot be a flag). */
    fun json(): JsonElement? = when (property.type) {
        "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive)
        "number" -> raw.toDoubleOrNull()?.let(::JsonPrimitive)
        "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        "array", "object" -> null
        else -> JsonPrimitive(raw)
    }
}

/** A command's input properties, found by property name or by flag (dashes, aliases). */
internal class MeridianCommandSchema(private val command: MeridianCommand) {
    private val properties: JsonObject? = command.tool.inputSchema?.get("properties") as? JsonObject

    fun property(name: String) = MeridianInputProperty(name, properties?.get(name) as? JsonObject)

    fun forFlag(flag: MeridianFlag) = property(command.flagAliases[flag.name] ?: flag.name.replace('-', '_'))

    /** False only when the schema lists its properties and [property] is not one of them. */
    fun accepts(property: MeridianInputProperty): Boolean = properties == null || property.name in properties
}

/**
 * Reads a command's argv words left to right: a word starting `--` is a flag (`--name=value`,
 * `--name value`, or a bare boolean `--name`); anything else is a positional.
 */
private class MeridianArgvReader(private val words: List<String>, private val schema: MeridianCommandSchema) {
    private var index = 0
    private val positionals = mutableListOf<String>()
    private val flags = mutableListOf<MeridianFlag>()

    fun read(): MeridianArgs {
        while (index < words.size) {
            val word = words[index++]
            if (word.startsWith("--") && word != "--") flags += nextFlag() else positionals += word
        }
        val inputFile = flags.lastOrNull { it.name == INPUT_FILE }?.value
        return MeridianArgs(positionals.toList(), flags.filterNot { it.name == INPUT_FILE }, inputFile)
    }

    /** The flag just read, taking the next word as its value when it needs one. */
    private fun nextFlag(): MeridianFlag {
        val word = words[index - 1]
        val body = word.removePrefix("--")
        val bare = MeridianFlag(body.substringBefore('='), null)
        return when {
            '=' in body -> bare.copy(value = body.substringAfter('='))
            schema.forFlag(bare).isBoolean -> bare
            index < words.size -> bare.copy(value = words[index++])
            else -> throw MeridianProblemException(MeridianInputProblem.MissingValue(word))
        }
    }

    private companion object {
        const val INPUT_FILE = "input-file"
    }
}

/**
 * Turns a command's argv words and stdin into the tool's input object (letta-mobile-jna0o.3).
 *
 * stdin is the JSON object, unchanged. Flags and positionals add scalar properties typed by the
 * tool's own input schema (`--dry-run` is `dry_run: true`, `--limit 5` is `limit: 5`). Arrays and
 * objects come only on stdin. A property given twice with different values is refused with its
 * JSON pointer. The tool's own validation stays authoritative: the router checks only what it
 * parsed itself, so a call reaches the tool exactly as the native call would.
 */
internal class MeridianInputBuilder(private val command: MeridianCommand) {
    private val schema = MeridianCommandSchema(command)

    fun parseArgs(words: List<String>): Result<MeridianArgs> = catchingProblems { MeridianArgvReader(words, schema).read() }

    /** The tool input: [MeridianArgs.stdin] parsed, then the argv scalars added. */
    fun build(args: MeridianArgs): Result<JsonObject> = catchingProblems {
        val merged = LinkedHashMap<String, JsonElement>(stdinObject(args))
        for ((property, value) in scalars(args)) {
            val existing = merged[property.name]
            if (existing != null && existing != value) throw MeridianProblemException(MeridianInputProblem.GivenTwice(property))
            merged[property.name] = value
        }
        JsonObject(merged)
    }

    private fun stdinObject(args: MeridianArgs): JsonObject {
        val text = args.stdin
        if (text.isNullOrBlank()) return JsonObject(emptyMap())
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MeridianProblemException(MeridianInputProblem.InvalidJson(e.message.orEmpty()))
        }
        return element as? JsonObject ?: throw MeridianProblemException(MeridianInputProblem.InvalidJson(null))
    }

    private fun scalars(args: MeridianArgs): List<Pair<MeridianInputProperty, JsonElement>> {
        args.positionals.getOrNull(command.positional.size)?.let {
            throw MeridianProblemException(MeridianInputProblem.UnexpectedArgument(it))
        }
        val positional = command.positional.zip(args.positionals).map { (name, raw) -> MeridianRawValue(schema.property(name), raw) }
        val flagged = args.flags.map { flag -> MeridianRawValue(schema.forFlag(flag), flag.value ?: TRUE) }
        return (positional + flagged).map { it.property to typed(it) }
    }

    private fun typed(value: MeridianRawValue): JsonElement {
        if (!schema.accepts(value.property)) throw MeridianProblemException(MeridianInputProblem.UnknownFlag(command, value.property))
        return value.json() ?: throw MeridianProblemException(MeridianInputProblem.WrongType(value.property))
    }

    /** [block], with a problem it raises answered as this command's structured error. */
    private inline fun <T> catchingProblems(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (problem: MeridianProblemException) {
        Result.failure(MeridianFailure(problem.problem.toError(command)))
    }

    private companion object {
        const val TRUE = "true"
    }
}

/** A problem raised while reading input, before it is tied to a command. */
internal class MeridianProblemException(val problem: MeridianInputProblem) : Exception(problem.message)

/** A refusal raised while parsing, carried out of a [Result]. */
internal class MeridianFailure(val error: MeridianError) : Exception(error.message)
