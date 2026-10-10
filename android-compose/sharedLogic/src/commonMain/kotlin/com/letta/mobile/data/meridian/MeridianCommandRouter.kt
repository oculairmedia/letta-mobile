package com.letta.mobile.data.meridian

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.meridian.MeridianCommandCatalog.CANVAS
import com.letta.mobile.data.meridian.MeridianCommandCatalog.GUIDE
import com.letta.mobile.data.meridian.MeridianCommandCatalog.HELP
import com.letta.mobile.data.meridian.MeridianCommandCatalog.SCHEMA
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one command surface behind both front doors (letta-mobile-jna0o.3; design
 * docs/design/on-demand-tools-via-meridian-cli.md section 4): the `meridian` CLI an agent runs
 * from its shell, and the `meridian` meta-tool. Transport-agnostic: it takes argv, stdin and the
 * caller scope, and answers stdout, stderr and an exit code.
 *
 * It has no tool logic of its own. Every command runs a tool through [ExternalToolRegistry.invoke]
 * with the caller's scope, the same entry a native tool call reaches: same validator, same ACL,
 * same result JSON. Its commands, help and schemas are generated from the registry's tools
 * ([MeridianCommandCatalog], [MeridianHelp]), so a host offers exactly the commands it can run.
 *
 * Capability boundary: only the registry's tools are reachable. There is no REST passthrough, no
 * admin and no profile command here.
 */
class MeridianCommandRouter(
    private val registry: ExternalToolRegistry,
    private val limits: MeridianLimits = MeridianLimits(),
    private val rateLimiter: MeridianRateLimiter = MeridianRateLimiter.Unlimited,
    private val inputFiles: MeridianInputFiles? = null,
) {
    /** The commands this host serves right now (plugins come and go). */
    fun commands(): List<MeridianCommand> = MeridianCommandCatalog.commands(registry.invocableTools())

    /** Runs one call: help, a guide or a schema, or a tool. */
    suspend fun execute(request: MeridianRequest): MeridianResponse {
        argvRefusal(request.argv)?.let { return it.toResponse() }
        val argv = MeridianArgv.withoutProgram(request.argv)
        val commands = commands()
        return reference(argv, commands) ?: runTool(argv, request, commands)
    }

    /**
     * [execute] for a front door that receives one command string and a structured input (the
     * meta-tool): [commandLine] is split like a shell's simple words, [input] is the stdin JSON.
     */
    suspend fun execute(commandLine: String, input: JsonObject?, caller: ExternalToolCaller): MeridianResponse {
        val argv = MeridianArgv.split(commandLine)
            ?: return MeridianError(MeridianErrorCode.USAGE, "the command has an unterminated quote").toResponse()
        return execute(MeridianRequest(argv, input?.toString(), caller))
    }

    // ---- Commands that run no tool: help, guides, schemas ----

    private fun reference(argv: List<String>, commands: List<MeridianCommand>): MeridianResponse? {
        val first = argv.firstOrNull()
        return when {
            first == null || first == HELP -> help(argv.drop(1), commands)
            argv.any(::isHelpFlag) -> help(argv.takeWhile { !it.startsWith("-") }, commands)
            first == SCHEMA -> schema(argv.drop(1), commands)
            first == CANVAS && argv.getOrNull(1) == SCHEMA -> schema(listOf(CANVAS) + argv.drop(2), commands)
            first == GUIDE -> guide(argv.drop(1), commands)
            else -> null
        }
    }

    private fun help(path: List<String>, commands: List<MeridianCommand>): MeridianResponse {
        if (path.isEmpty()) return text(MeridianHelp.top(commands))
        val exact = commands.firstOrNull { it.path == path }
        return when {
            exact != null -> text(MeridianHelp.command(exact))
            path.size == 1 && commands.any { it.path.first() == path.single() } -> text(MeridianHelp.group(path.single(), commands))
            path == listOf(GUIDE) -> text(MeridianHelp.guideIndex(commands))
            else -> unknown(path, commands)
        }
    }

    private fun schema(path: List<String>, commands: List<MeridianCommand>): MeridianResponse {
        val command = commands.firstOrNull { it.path == path } ?: return unknown(path, commands)
        return MeridianResponse(MeridianExit.OK, (command.tool.inputSchema ?: EMPTY_SCHEMA).toString())
    }

    /** `guide` lists the topics; `guide compose` is the tool's own answer (a command); the rest are contract text. */
    private fun guide(rest: List<String>, commands: List<MeridianCommand>): MeridianResponse? {
        val topic = rest.singleOrNull() ?: return if (rest.isEmpty()) text(MeridianHelp.guideIndex(commands)) else null
        if (commands.any { it.path == listOf(GUIDE, topic) }) return null
        val known = topic in MeridianHelp.guideTopics(commands)
        val body = MeridianHelp.guideText(topic)?.takeIf { known } ?: return unknown(listOf(GUIDE, topic), commands)
        return text(body.trimEnd() + "\n")
    }

    // ---- Commands that run a tool ----

    private suspend fun runTool(argv: List<String>, request: MeridianRequest, commands: List<MeridianCommand>): MeridianResponse {
        val (command, rest) = MeridianCommandCatalog.resolve(commands, argv) ?: return unknown(argv, commands)
        val input = toolInput(command, rest, request.stdin).getOrElse { return failureResponse(it) }
        val admission = rateLimiter.admit(request.caller, command.display)
        if (admission is MeridianAdmission.Limited) {
            return MeridianError(
                MeridianErrorCode.RATE_LIMITED,
                "too many meridian calls in this conversation",
                command = command.display,
                retryAfterMs = admission.retryAfterMs,
                hint = "Wait ${admission.retryAfterMs} ms before the next call.",
            ).toResponse()
        }
        val result = registry.invoke(command.toolName, input, request.caller)
        if (registry.invocableTools().none { it.name == command.toolName }) {
            return MeridianError(MeridianErrorCode.HOST_UNAVAILABLE, "${command.toolName} is no longer served here", command = command.display).toResponse()
        }
        return MeridianToolOutcome.response(command, result, limits)
    }

    private suspend fun toolInput(command: MeridianCommand, rest: List<String>, stdin: String?): Result<JsonObject> {
        val builder = MeridianInputBuilder(command)
        val args = builder.parseArgs(rest).getOrElse { return Result.failure(it) }
        val source = inputSource(command, args.inputFile, stdin).getOrElse { return Result.failure(it) }
        return builder.build(args.copy(stdin = source))
    }

    /** stdin, or the `--input-file` the front door can read; never both. */
    private suspend fun inputSource(command: MeridianCommand, inputFile: String?, stdin: String?): Result<String?> {
        val text = when {
            inputFile == null -> stdin
            !stdin.isNullOrBlank() -> return refuse(command, MeridianErrorCode.USAGE, "give the input on stdin or with --input-file, not both")
            else -> {
                val reader = inputFiles
                    ?: return refuse(command, MeridianErrorCode.USAGE, "--input-file is not available here; pipe the file on stdin")
                reader.read(inputFile)
                    ?: return refuse(command, MeridianErrorCode.INVALID_INPUT, "cannot read --input-file $inputFile")
            }
        }
        val size = text?.encodeToByteArray()?.size ?: 0
        if (size > limits.maxInputBytes) {
            return refuse(command, MeridianErrorCode.INPUT_TOO_LARGE, "the input is $size bytes; at most ${limits.maxInputBytes}")
        }
        return Result.success(text)
    }

    private fun <T> refuse(command: MeridianCommand, code: MeridianErrorCode, message: String): Result<T> =
        Result.failure(MeridianFailure(MeridianError(code, message, command = command.display)))

    private fun failureResponse(failure: Throwable): MeridianResponse =
        (failure as? MeridianFailure)?.error?.toResponse()
            ?: MeridianError(MeridianErrorCode.USAGE, failure.message ?: "the command could not be read").toResponse()

    // ---- Shared answers ----

    private fun argvRefusal(argv: List<String>): MeridianError? = when {
        argv.size > limits.maxArgs -> MeridianError(MeridianErrorCode.USAGE, "${argv.size} arguments; at most ${limits.maxArgs}")
        argv.any { it.encodeToByteArray().size > limits.maxArgBytes } -> MeridianError(
            MeridianErrorCode.INPUT_TOO_LARGE,
            "an argument is over ${limits.maxArgBytes} bytes; put large input in the stdin JSON",
        )
        else -> null
    }

    /** An unknown path: a group this host does not serve is unavailable; anything else is a usage error. */
    private fun unknown(path: List<String>, commands: List<MeridianCommand>): MeridianResponse {
        val group = path.firstOrNull()
        val known = MeridianCommandCatalog.groups.any { it.name == group }
        if (known && commands.none { it.path.first() == group }) {
            return MeridianError(MeridianErrorCode.HOST_UNAVAILABLE, "this host serves no $group commands", command = group).toResponse()
        }
        val hint = if (known) "Run: meridian $group --help" else "Run: meridian --help"
        return MeridianError(
            MeridianErrorCode.UNKNOWN_COMMAND,
            "unknown command: ${path.joinToString(" ").ifEmpty { "(none)" }}",
            hint = hint,
        ).toResponse()
    }

    private fun text(body: String) = MeridianResponse(MeridianExit.OK, body)

    private fun isHelpFlag(word: String) = word == "--help" || word == "-h"

    private companion object {
        val EMPTY_SCHEMA = JsonObject(mapOf("type" to JsonPrimitive("object")))
    }
}
