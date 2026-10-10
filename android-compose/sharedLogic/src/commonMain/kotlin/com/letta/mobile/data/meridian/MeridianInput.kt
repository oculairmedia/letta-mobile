package com.letta.mobile.data.meridian

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads `--input-file PATH` for a front door that has a filesystem (the wrapper's endpoint binds
 * one); null means the file could not be read. The router never reads files itself.
 */
fun interface MeridianInputFiles {
    suspend fun read(path: String): String?
}

/** One `--flag` as written: its name, and its value (null for a bare boolean flag). */
internal data class MeridianFlag(val name: String, val value: String?)

/** The words after a command: positionals, `--flag value` / `--flag=value` scalars, and `--input-file`. */
internal data class MeridianArgs(
    val positionals: List<String>,
    val flags: List<MeridianFlag>,
    val inputFile: String?,
)

/** One input property of a command's tool, as the tool's input schema describes it. */
internal class MeridianInputProperty(val name: String, private val schema: JsonObject?) {
    val type: String? = (schema?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val pointer: String get() = "/$name"
    val flag: String get() = "--${name.replace('_', '-')}"
    val isBoolean: Boolean get() = type == "boolean"

    /** [raw] typed as the schema says, or null when it is not that type (or cannot be a flag). */
    fun parse(raw: String): JsonElement? = when (type) {
        "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive)
        "number" -> raw.toDoubleOrNull()?.let(::JsonPrimitive)
        "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
        "array", "object" -> null
        else -> JsonPrimitive(raw)
    }

    fun expected(): String = when (type) {
        "array", "object" -> "given in the stdin JSON (an $type cannot be a flag)"
        "integer" -> "an integer"
        else -> "a $type"
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
    private val properties: JsonObject? = command.tool.inputSchema?.get("properties") as? JsonObject

    fun parseArgs(words: List<String>): Result<MeridianArgs> {
        val positionals = mutableListOf<String>()
        val flags = mutableListOf<MeridianFlag>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            if (isFlag(word)) {
                val flag = readFlag(word, words.getOrNull(index + 1)) ?: return usage("$word needs a value")
                flags += flag
                index += if (flag.value != null && '=' !in word && !isBooleanFlag(flag.name)) 2 else 1
            } else {
                positionals += word
                index += 1
            }
        }
        val inputFile = flags.lastOrNull { it.name == INPUT_FILE }?.value
        return Result.success(MeridianArgs(positionals, flags.filterNot { it.name == INPUT_FILE }, inputFile))
    }

    private fun isFlag(word: String) = word.startsWith("--") && word != "--"

    /** The flag [word] names, taking [next] as its value when it needs one; null when one is missing. */
    private fun readFlag(word: String, next: String?): MeridianFlag? {
        val body = word.removePrefix("--")
        val name = body.substringBefore('=')
        return when {
            '=' in body -> MeridianFlag(name, body.substringAfter('='))
            isBooleanFlag(name) -> MeridianFlag(name, null)
            else -> next?.let { MeridianFlag(name, it) }
        }
    }

    /** The tool input: [stdin] parsed, then [args]' scalars added. */
    fun build(stdin: String?, args: MeridianArgs): Result<JsonObject> {
        val base = parseStdin(stdin).getOrElse { return Result.failure(it) }
        val extra = scalars(args).getOrElse { return Result.failure(it) }
        val merged = LinkedHashMap<String, JsonElement>(base)
        for ((property, value) in extra) {
            val existing = merged[property.name]
            if (existing != null && existing != value) {
                return failure(MeridianErrorCode.INVALID_INPUT, "${property.name} is given both in the stdin JSON and as an argument", property)
            }
            merged[property.name] = value
        }
        return Result.success(JsonObject(merged))
    }

    private fun parseStdin(stdin: String?): Result<JsonObject> {
        if (stdin.isNullOrBlank()) return Result.success(JsonObject(emptyMap()))
        val element = try {
            Json.parseToJsonElement(stdin)
        } catch (e: SerializationException) {
            return invalidJson("stdin is not valid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
        }
        return (element as? JsonObject)?.let { Result.success(it) } ?: invalidJson("stdin must be one JSON object")
    }

    private fun scalars(args: MeridianArgs): Result<List<Pair<MeridianInputProperty, JsonElement>>> {
        if (args.positionals.size > command.positional.size) {
            return usage("unexpected argument '${args.positionals[command.positional.size]}'")
        }
        val named = command.positional.zip(args.positionals).map { (name, raw) -> property(name) to raw } +
            args.flags.map { flag -> property(command.flagAliases[flag.name] ?: flag.name.replace('-', '_')) to (flag.value ?: TRUE) }
        return Result.success(named.map { (property, raw) -> property to (typed(property, raw).getOrElse { return Result.failure(it) }) })
    }

    private fun property(name: String) = MeridianInputProperty(name, properties?.get(name) as? JsonObject)

    private fun isBooleanFlag(flag: String): Boolean = property(command.flagAliases[flag] ?: flag.replace('-', '_')).isBoolean

    private fun typed(property: MeridianInputProperty, raw: String): Result<JsonElement> {
        if (properties != null && property.name !in properties) {
            return failure(MeridianErrorCode.INVALID_INPUT, "${command.display} takes no ${property.flag}", property)
        }
        return property.parse(raw)?.let { Result.success(it) }
            ?: failure(MeridianErrorCode.INVALID_INPUT, "${property.flag} must be ${property.expected()}", property)
    }

    private fun <T> usage(message: String): Result<T> = failure(MeridianErrorCode.USAGE, message)

    private fun <T> invalidJson(message: String): Result<T> =
        Result.failure(MeridianFailure(error(MeridianErrorCode.INVALID_JSON, message).copy(pointer = "")))

    private fun <T> failure(code: MeridianErrorCode, message: String, property: MeridianInputProperty? = null): Result<T> =
        Result.failure(MeridianFailure(error(code, message).copy(pointer = property?.pointer)))

    private fun error(code: MeridianErrorCode, message: String) =
        MeridianError(code, message, command = command.display, hint = hintFor(code))

    private fun hintFor(code: MeridianErrorCode): String? = when (code) {
        MeridianErrorCode.INVALID_JSON ->
            "Pass the input as one JSON object on stdin with a quoted heredoc: meridian ${command.display} <<'JSON' ... JSON"
        MeridianErrorCode.USAGE, MeridianErrorCode.INVALID_INPUT -> "See: meridian ${command.display} --help"
        else -> null
    }

    companion object {
        const val INPUT_FILE = "input-file"
        private const val TRUE = "true"
    }
}

/** A refusal raised while parsing, carried out of a [Result]. */
internal class MeridianFailure(val error: MeridianError) : Exception(error.message)
