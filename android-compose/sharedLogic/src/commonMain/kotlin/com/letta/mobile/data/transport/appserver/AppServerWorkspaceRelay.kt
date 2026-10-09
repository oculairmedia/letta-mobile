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
    // First character not `-`: a ref becomes a `git show <sha>` argument, and `--output=…` is an option.
    private val REF = Regex("^[A-Za-z0-9._/~^@{}][A-Za-z0-9._/~^@{}-]*$")
    private val AGENT_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val SECRET_KEY = Regex("^[A-Z_][A-Z0-9_]*$")
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

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
        if (result.toString().encodeToByteArray().size > MAX_RESULT_BYTES) {
            throw WorkspaceRelayException("the App Server's answer is larger than the relay's ${MAX_RESULT_BYTES / (1024 * 1024)} MiB cap")
        }
        return result
    }

    /**
     * Host side: a missing-file answer (Node's `ENOENT: no such file or directory, open '<path>'`)
     * becomes a typed `error_code: not_found` without the host path. On the MemFS methods every
     * other failure (EACCES, EISDIR, git errors…) also becomes a fixed sentence, because Node and
     * git errors quote absolute host paths. Every other frame is unchanged.
     */
    fun normalizeFrame(method: WorkspaceRelayMethod, frame: JsonObject): JsonObject = when {
        frame.isMissingFileAnswer() ->
            JsonObject(frame + mapOf("error" to JsonPrimitive(NOT_FOUND_MESSAGE), "error_code" to JsonPrimitive(NOT_FOUND_CODE)))
        frame.isFailure() && method.isMemfs ->
            JsonObject(frame + mapOf("error" to JsonPrimitive(MEMFS_FAILED_MESSAGE), "error_code" to JsonPrimitive(FAILED_CODE)))
        else -> frame
    }

    private val WorkspaceRelayMethod.isMemfs: Boolean
        get() = access == WorkspaceRelayAccess.MemoryRead || access == WorkspaceRelayAccess.MemoryWrite

    private fun JsonObject.isFailure(): Boolean = (this["success"] as? JsonPrimitive)?.contentOrNull == "false"

    private fun JsonObject.isMissingFileAnswer(): Boolean {
        if ((this["success"] as? JsonPrimitive)?.contentOrNull != "false") return false
        val error = (this["error"] as? JsonPrimitive)?.contentOrNull ?: return false
        return isMissingFile(error)
    }

    const val NOT_FOUND_CODE: String = "not_found"
    const val NOT_FOUND_MESSAGE: String = "not found"
    const val FAILED_CODE: String = "failed"
    const val MEMFS_FAILED_MESSAGE: String = "the memory operation failed on the host"

    private fun isMissingFile(error: String): Boolean = "ENOENT" in error || error.contains("no such file", ignoreCase = true)

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
                RelayField.AgentId.requires(command.agentId.isAgentId())
                RelayField.Path.requires(command.path.isMemoryPath(write = true))
                RelayField.Content.requires(command.content.length <= MAX_WRITE_CHARS)
                RelayField.Encoding.requires(command.encoding.isEncoding())
                RelayField.CommitMessage.requires((command.commitMessage?.length ?: 0) <= MAX_MEMORY_PATH_CHARS)
            }
            else -> throw WorkspaceRelayException("this command is not relayed over Iroh")
        }
    }

    private fun validateMemfs(command: AppServerMemfsCommand) {
        RelayField.AgentId.requires(command.agentId.isAgentId())
        when (command) {
            is AppServerMemfsCommand.ListMemory, is AppServerMemfsCommand.EnableMemfs -> Unit
            is AppServerMemfsCommand.ReadMemoryFile -> {
                RelayField.Path.requires(command.path.isMemoryPath())
                RelayField.Encoding.requires(command.encoding.isEncoding())
            }
            is AppServerMemfsCommand.MemoryHistory -> {
                RelayField.FilePath.requires(command.filePath?.isMemoryPath() != false)
                RelayField.Limit.requires(command.limit?.let { it in 1..MAX_HISTORY_LIMIT } != false)
            }
            is AppServerMemfsCommand.MemoryCommitDiff -> RelayField.Sha.requires(command.sha.isRef())
            is AppServerMemfsCommand.MemoryFileAtRef -> {
                RelayField.FilePath.requires(command.filePath.isMemoryPath())
                RelayField.Ref.requires(command.ref.isRef())
            }
        }
    }

    private fun validateSecret(command: AppServerSecretCommand) {
        RelayField.AgentId.requires(command.agentId.isAgentId())
        if (command !is AppServerSecretCommand.SecretApply) return
        RelayField.SecretBatch.requires(command.set.size + command.unset.size <= MAX_SECRET_KEYS)
        RelayField.SetKey.requires(command.set.keys.all(SECRET_KEY::matches))
        RelayField.SetValue.requires(command.set.values.all { it.length <= MAX_SECRET_VALUE_CHARS })
        RelayField.UnsetKey.requires(command.unset.all(SECRET_KEY::matches))
    }

    private fun validateFile(command: AppServerFileCommand) {
        when (command) {
            is AppServerFileCommand.SearchFiles -> {
                RelayField.Query.requires(command.query.length <= MAX_QUERY_CHARS && command.query.isPlain())
                RelayField.MaxResults.requires(command.maxResults?.let { it in 1..MAX_SEARCH_RESULTS } != false)
                RelayField.Cwd.requires(command.cwd?.isAbsoluteFilePath() != false)
            }
            is AppServerFileCommand.ReadFile -> {
                // Absolute only: a relative path would resolve against the host process's cwd. A
                // memory tool's relative path is read with memfs.read, against the agent's root.
                RelayField.Path.requires(command.path.isAbsoluteFilePath())
                RelayField.Encoding.requires(command.encoding.isEncoding())
            }
        }
    }

    /** The relayed fields the caps name in their errors. */
    private enum class RelayField(val wireName: String) {
        AgentId("agent_id"), Path("path"), FilePath("file_path"), Content("content"), Encoding("encoding"),
        CommitMessage("commit_message"), Limit("limit"), Sha("sha"), Ref("ref"), SecretBatch("set/unset"),
        SetKey("set key"), SetValue("set value"), UnsetKey("unset key"), Query("query"), MaxResults("max_results"),
        Cwd("cwd"),
        ;

        fun requires(ok: Boolean) {
            if (!ok) throw WorkspaceRelayException("$wireName is not allowed by the workspace relay")
        }
    }

    /** Letta ids look like `agent-<uuid>`; `.` and `..` would escape the per-agent memory root. */
    private fun String.isAgentId(): Boolean = AGENT_ID.matches(this)

    /**
     * Relative to the agent's memory root: no absolute path, drive, `..` segment, or percent-encoding
     * (`%2e%2e`). The App Server joins it to the root and rejects what still escapes.
     *
     * The root is a git work tree whose `git add` the host runs: no segment may be `.git` (its
     * config can name a filter or fsmonitor command), compared case-insensitively after trimming
     * trailing dots and spaces; a write may not create `.gitattributes` / `.gitmodules` either.
     */
    private fun String.isMemoryPath(write: Boolean = false): Boolean =
        isNotBlank() &&
            length <= MAX_MEMORY_PATH_CHARS &&
            isPlain() &&
            !startsWith('/') && !startsWith('\\') && ':' !in this && '%' !in this &&
            replace('\\', '/').split('/').all { segment ->
                val name = segment.trimEnd('.', ' ').lowercase()
                (name.isNotEmpty() || segment.isEmpty()) &&
                    name != ".git" &&
                    !(write && (name == ".gitattributes" || name == ".gitmodules"))
            }

    /** A POSIX, UNC or drive-letter absolute path. */
    private fun String.isAbsoluteFilePath(): Boolean =
        isNotBlank() && length <= MAX_FILE_PATH_CHARS && isPlain() &&
            (startsWith('/') || startsWith("\\\\") || WINDOWS_DRIVE.containsMatchIn(this))

    private fun String.isRef(): Boolean = length in 1..MAX_REF_CHARS && REF.matches(this) && ".." !in this

    private fun String?.isEncoding(): Boolean = this == null || this in ENCODINGS

    private fun String.isPlain(): Boolean = none { it.isISOControl() }

    private fun encode(command: AppServerCommand): JsonObject =
        AppServerProtocol.json.encodeToJsonElement(AppServerCommand.serializer(), command).jsonObject
}
