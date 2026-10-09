package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** What a relayed workspace method touches; the Iroh host maps it to a peer capability. */
enum class WorkspaceRelayAccess { MemoryRead, MemoryWrite, Files, Secrets }

/**
 * letta-mobile-bzvro.37: the admin_rpc methods an Iroh host relays to its App Server for the
 * agent-workspace features (MemFS browser, secrets vault, workspace files). One method per
 * App Server command, and nothing else: the method fixes the command type, so a client cannot
 * smuggle a different command through a method it is allowed to call.
 */
enum class WorkspaceRelayMethod(
    val method: String,
    val commandType: String,
    val access: WorkspaceRelayAccess,
) {
    ListMemory("memfs.list", "list_memory", WorkspaceRelayAccess.MemoryRead),
    ReadMemoryFile("memfs.read", "read_memory_file", WorkspaceRelayAccess.MemoryRead),
    MemoryHistory("memfs.history", "memory_history", WorkspaceRelayAccess.MemoryRead),
    MemoryCommitDiff("memfs.commit_diff", "memory_commit_diff", WorkspaceRelayAccess.MemoryRead),
    MemoryFileAtRef("memfs.file_at_ref", "memory_file_at_ref", WorkspaceRelayAccess.MemoryRead),
    EnableMemfs("memfs.enable", "enable_memfs", WorkspaceRelayAccess.MemoryWrite),
    WriteMemoryFile("memfs.write", "write_memory_file", WorkspaceRelayAccess.MemoryWrite),
    SecretList("secret.list", "secret_list", WorkspaceRelayAccess.Secrets),
    SecretApply("secret.apply", "secret_apply", WorkspaceRelayAccess.Secrets),
    SearchFiles("workspace.search_files", "search_files", WorkspaceRelayAccess.Files),
    ReadFile("workspace.read_file", "read_file", WorkspaceRelayAccess.Files),
    ;

    /** Changes nothing on the host, so a client may retry it after a lost connection. */
    val isRead: Boolean
        get() = access == WorkspaceRelayAccess.MemoryRead || access == WorkspaceRelayAccess.Files || this == SecretList

    companion object {
        private val byMethod = entries.associateBy { it.method }
        private val byCommandType = entries.associateBy { it.commandType }

        /** The allowlisted method named [method], or null: anything else is not relayed. */
        fun forMethod(method: String): WorkspaceRelayMethod? = byMethod[method]

        fun forCommandType(type: String): WorkspaceRelayMethod? = byCommandType[type]

        val methods: Set<String> = byMethod.keys
    }
}

/** A relayed request or answer that breaks the relay's rules. The message never quotes a value. */
class WorkspaceRelayException(message: String) : IllegalArgumentException(message)

/**
 * letta-mobile-bzvro.37: the wire contract between an Iroh client and the host relaying its
 * agent-workspace commands. Shared by both ends so they cannot drift.
 *
 * - Request: admin_rpc `method` = [WorkspaceRelayMethod.method]; `params` = the App Server
 *   command's own fields, without `type` and `request_id` (the host sets both).
 * - Answer: `result` = `{"frames": [...]}`, the App Server's raw answering frames (every page of a
 *   streamed `list_memory`), at most [MAX_RESULT_BYTES] in all.
 *
 * [decodeCommand] is the allowlist and the size caps, applied by the host before anything reaches
 * the App Server. Its errors name the field, never the value: secret values ride `secret.apply`.
 */
object WorkspaceRelay {
    /** Advertised in the host's auth response when it relays these methods. */
    const val CAPABILITY: String = "workspace_relay_v1"

    const val MAX_RESULT_BYTES: Int = 8 * 1024 * 1024
    const val MAX_ID_CHARS: Int = 128
    const val MAX_MEMORY_PATH_CHARS: Int = 1024
    const val MAX_REF_CHARS: Int = 200
    const val MAX_HISTORY_LIMIT: Int = 500
    const val MAX_FILE_PATH_CHARS: Int = 4096
    const val MAX_QUERY_CHARS: Int = 512
    const val MAX_SEARCH_RESULTS: Int = 200
    const val MAX_WRITE_CHARS: Int = 1_000_000
    const val MAX_SECRET_KEYS: Int = 100
    const val MAX_SECRET_VALUE_CHARS: Int = 65_536

    private const val FRAMES = "frames"
    private const val TYPE = "type"
    private const val REQUEST_ID = "request_id"
    private val ENCODINGS = setOf("utf8", "base64")
    private val REF = Regex("^[A-Za-z0-9._/~^@{}-]+$")
    private val SECRET_KEY = Regex("^[A-Z_][A-Z0-9_]*$")

    /** The method that relays [command]; fails for a command the relay does not carry. */
    fun methodFor(command: AppServerCommand): WorkspaceRelayMethod {
        val type = encode(command)[TYPE]?.let { (it as? JsonPrimitive)?.contentOrNull }
        return type?.let(WorkspaceRelayMethod::forCommandType)
            ?: throw WorkspaceRelayException("${type ?: "this command"} is not relayed over Iroh")
    }

    /** The admin_rpc params for [command]: its fields without `type` and `request_id`. */
    fun encodeParams(command: AppServerCommand): JsonObject =
        JsonObject(encode(command) - TYPE - REQUEST_ID)

    /**
     * Host side: the App Server command [method] stands for, built from [params] with the host's
     * own [requestId], then checked against the caps. Throws [WorkspaceRelayException].
     */
    fun decodeCommand(method: WorkspaceRelayMethod, params: JsonObject?, requestId: String): AppServerCommand {
        val envelope = JsonObject(
            (params ?: JsonObject(emptyMap())) + mapOf(TYPE to JsonPrimitive(method.commandType), REQUEST_ID to JsonPrimitive(requestId)),
        )
        // No cause: a kotlinx decoding error quotes its input, and the input may hold secret values.
        val command = runCatching { AppServerProtocol.json.decodeFromJsonElement(AppServerCommand.serializer(), envelope) }
            .getOrElse { throw WorkspaceRelayException("invalid params for ${method.method}") }
        validate(command)
        return command
    }

    /** Host side: the admin_rpc result carrying [frames]; fails past [MAX_RESULT_BYTES]. */
    fun encodeResult(frames: List<JsonObject>): JsonObject {
        val result = buildJsonObject { put(FRAMES, JsonArray(frames)) }
        if (result.toString().length > MAX_RESULT_BYTES) {
            throw WorkspaceRelayException("the App Server's answer is larger than the relay's ${MAX_RESULT_BYTES / (1024 * 1024)} MiB cap")
        }
        return result
    }

    /** Client side: the relayed frames, each carrying the client's own [requestId]. */
    fun decodeResult(result: JsonElement?, requestId: String): List<JsonObject> {
        val frames = (result as? JsonObject)?.get(FRAMES) as? JsonArray
            ?: throw WorkspaceRelayException("the Iroh host sent no frames")
        return frames.map { frame ->
            val obj = frame as? JsonObject ?: throw WorkspaceRelayException("the Iroh host sent a malformed frame")
            JsonObject(obj + (REQUEST_ID to JsonPrimitive(requestId)))
        }
    }

    /** The caps every relayed command must meet. Messages name the field only. */
    fun validate(command: AppServerCommand) {
        when (command) {
            is AppServerMemfsCommand -> validateMemfs(command)
            is AppServerSecretCommand -> validateSecret(command)
            is AppServerFileCommand -> validateFile(command)
            is AppServerCommand.WriteMemoryFile -> {
                requireAgentId(command.agentId)
                requireMemoryPath(command.path, "path")
                require(command.content.length <= MAX_WRITE_CHARS, "content")
                requireEncoding(command.encoding)
                require((command.commitMessage?.length ?: 0) <= MAX_MEMORY_PATH_CHARS, "commit_message")
            }
            else -> throw WorkspaceRelayException("this command is not relayed over Iroh")
        }
    }

    private fun validateMemfs(command: AppServerMemfsCommand) {
        requireAgentId(command.agentId)
        when (command) {
            is AppServerMemfsCommand.ListMemory, is AppServerMemfsCommand.EnableMemfs -> Unit
            is AppServerMemfsCommand.ReadMemoryFile -> {
                requireMemoryPath(command.path, "path")
                requireEncoding(command.encoding)
            }
            is AppServerMemfsCommand.MemoryHistory -> {
                command.filePath?.let { requireMemoryPath(it, "file_path") }
                command.limit?.let { require(it in 1..MAX_HISTORY_LIMIT, "limit") }
            }
            is AppServerMemfsCommand.MemoryCommitDiff -> requireRef(command.sha, "sha")
            is AppServerMemfsCommand.MemoryFileAtRef -> {
                requireMemoryPath(command.filePath, "file_path")
                requireRef(command.ref, "ref")
            }
        }
    }

    private fun validateSecret(command: AppServerSecretCommand) {
        requireAgentId(command.agentId)
        if (command !is AppServerSecretCommand.SecretApply) return
        require(command.set.size + command.unset.size <= MAX_SECRET_KEYS, "set/unset")
        command.set.forEach { (key, value) ->
            require(SECRET_KEY.matches(key), "set key")
            require(value.length <= MAX_SECRET_VALUE_CHARS, "set value")
        }
        command.unset.forEach { key -> require(SECRET_KEY.matches(key), "unset key") }
    }

    private fun validateFile(command: AppServerFileCommand) {
        when (command) {
            is AppServerFileCommand.SearchFiles -> {
                require(command.query.length <= MAX_QUERY_CHARS && command.query.isPlain(), "query")
                command.maxResults?.let { require(it in 1..MAX_SEARCH_RESULTS, "max_results") }
                command.cwd?.let { require(it.isNotBlank() && it.length <= MAX_FILE_PATH_CHARS && it.isPlain(), "cwd") }
            }
            is AppServerFileCommand.ReadFile -> {
                require(command.path.isNotBlank() && command.path.length <= MAX_FILE_PATH_CHARS && command.path.isPlain(), "path")
                requireEncoding(command.encoding)
            }
        }
    }

    private fun requireAgentId(agentId: String) =
        require(
            agentId.isNotBlank() && agentId.length <= MAX_ID_CHARS && agentId.isPlain() && '/' !in agentId && '\\' !in agentId,
            "agent_id",
        )

    /** Relative to the agent's memory root: no absolute path, drive, or `..` segment. */
    private fun requireMemoryPath(path: String, field: String) {
        val segments = path.replace('\\', '/').split('/')
        val ok = path.isNotBlank() &&
            path.length <= MAX_MEMORY_PATH_CHARS &&
            path.isPlain() &&
            !path.startsWith('/') && !path.startsWith('\\') && ':' !in path &&
            segments.none { it == ".." }
        require(ok, field)
    }

    private fun requireRef(ref: String, field: String) =
        require(ref.length in 1..MAX_REF_CHARS && REF.matches(ref) && ".." !in ref, field)

    private fun requireEncoding(encoding: String?) = require(encoding == null || encoding in ENCODINGS, "encoding")

    private fun String.isPlain(): Boolean = none { it.isISOControl() }

    private fun require(ok: Boolean, field: String) {
        if (!ok) throw WorkspaceRelayException("$field is not allowed by the workspace relay")
    }

    private fun encode(command: AppServerCommand): JsonObject =
        AppServerProtocol.json.encodeToJsonElement(AppServerCommand.serializer(), command).jsonObject
}
