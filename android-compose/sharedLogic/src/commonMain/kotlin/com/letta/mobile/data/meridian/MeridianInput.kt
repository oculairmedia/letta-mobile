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

/** The words after a command: positionals, `--flag value` / `--flag=value` scalars, and `--input-file`. */
internal data class MeridianArgs(
    val positionals: List<String>,
    val flags: List<Pair<String, String?>>,
    val inputFile: String?,
)

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
    private val properties: JsonObject? =
        (command.tool.inputSchema?.get("properties") as? JsonObject)

    fun parseArgs(words: List<String>): Result<MeridianArgs> {
        val positionals = mutableListOf<String>()
        val flags = mutableListOf<Pair<String, String?>>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            if (!word.startsWith("--") || word == "--") {
                positionals += word
                index += 1
                continue
            }
            val flag = word.removePrefix("--")
            val name = flag.substringBefore('=')
            val inline = if ('=' in flag) flag.substringAfter('=') else null
            val takesValue = inline == null && !isBooleanFlag(name)
            if (takesValue && index + 1 >= words.size) return usage("--$name needs a value")
            flags += name to (inline ?: if (takesValue) words[index + 1] else null)
            index += if (takesValue) 2 else 1
        }
        val inputFile = flags.lastOrNull { it.first == INPUT_FILE }?.second
        return Result.success(MeridianArgs(positionals, flags.filterNot { it.first == INPUT_FILE }, inputFile))
    }

    /** The tool input: [stdin] parsed, then [args]' scalars added. */
    fun build(stdin: String?, args: MeridianArgs): Result<JsonObject> {
        val base = parseStdin(stdin).getOrElse { return Result.failure(it) }
        val extra = scalars(args).getOrElse { return Result.failure(it) }
        val merged = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in extra) {
            val existing = merged[key]
            if (existing != null && existing != value) {
                return failure(MeridianErrorCode.INVALID_INPUT, "$key is given both in the stdin JSON and as an argument", "/$key")
            }
            merged[key] = value
        }
        return Result.success(JsonObject(merged))
    }

    private fun parseStdin(stdin: String?): Result<JsonObject> {
        if (stdin.isNullOrBlank()) return Result.success(JsonObject(emptyMap()))
        val element = try {
            Json.parseToJsonElement(stdin)
        } catch (e: SerializationException) {
            return failure(MeridianErrorCode.INVALID_JSON, "stdin is not valid JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}", "")
        }
        val obj = element as? JsonObject
            ?: return failure(MeridianErrorCode.INVALID_JSON, "stdin must be one JSON object", "")
        return Result.success(obj)
    }

    private fun scalars(args: MeridianArgs): Result<List<Pair<String, JsonElement>>> {
        if (args.positionals.size > command.positional.size) {
            return usage("unexpected argument '${args.positionals[command.positional.size]}'")
        }
        val named = command.positional.zip(args.positionals) + args.flags.map { (flag, value) -> property(flag) to (value ?: TRUE) }
        return Result.success(named.map { (key, raw) -> key to (typed(key, raw).getOrElse { return Result.failure(it) }) })
    }

    /** The input property a flag names: an alias, or the flag with dashes as underscores. */
    private fun property(flag: String): String = command.flagAliases[flag] ?: flag.replace('-', '_')

    private fun isBooleanFlag(flag: String): Boolean = typeOf(property(flag)) == "boolean"

    private fun typeOf(key: String): String? =
        (properties?.get(key) as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }

    private fun typed(key: String, raw: String): Result<JsonElement> {
        if (properties != null && key !in properties) {
            return failure(MeridianErrorCode.INVALID_INPUT, "${command.display} takes no --${key.replace('_', '-')}", "/$key")
        }
        val value: JsonElement? = when (typeOf(key)) {
            "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive)
            "number" -> raw.toDoubleOrNull()?.let(::JsonPrimitive)
            "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
            "array", "object" -> null
            else -> JsonPrimitive(raw)
        }
        return value?.let { Result.success(it) }
            ?: failure(MeridianErrorCode.INVALID_INPUT, "--${key.replace('_', '-')} must be ${expected(key)}", "/$key")
    }

    private fun expected(key: String): String = when (val type = typeOf(key)) {
        "array", "object" -> "given in the stdin JSON (an $type cannot be a flag)"
        "integer" -> "an integer"
        else -> "a $type"
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (isString) content else null

    private fun <T> usage(message: String): Result<T> = failure(MeridianErrorCode.USAGE, message, null)

    private fun <T> failure(code: MeridianErrorCode, message: String, pointer: String?): Result<T> =
        Result.failure(MeridianFailure(MeridianError(code, message, command = command.display, pointer = pointer, hint = hintFor(code))))

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
