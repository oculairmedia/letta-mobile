package com.letta.mobile.data.meridian.endpoint

import com.letta.mobile.data.meridian.MeridianError
import com.letta.mobile.data.meridian.MeridianErrorCode
import com.letta.mobile.data.meridian.MeridianResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The `meridian/tools/1` wire (letta-mobile-jna0o.4): what the `meridian` shim in an agent's shell
 * sends the host's local endpoint, and what it gets back. One request per connection, each side one
 * line of UTF-8 JSON terminated by `\n` (JSON escapes every newline inside a string, so a line is
 * always one whole message).
 *
 * The endpoint exposes the Meridian command router and nothing else: there is no REST passthrough,
 * no admin and no profile surface on this wire.
 */
object MeridianToolsWire {
    /** The protocol id a client may send in [MeridianToolsWireRequest.protocol]. */
    const val PROTOCOL = "meridian/tools/1"

    /**
     * The longest request line the endpoint reads: the router's own input cap plus room for argv
     * and the envelope. A longer line is refused unread.
     */
    const val MAX_REQUEST_LINE_BYTES: Int = 9 * 1024 * 1024 + 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Parses one request line, or answers why it cannot be one. */
    fun decodeRequest(line: String): Result<MeridianToolsWireRequest> {
        val request = try {
            json.decodeFromString(MeridianToolsWireRequest.serializer(), line)
        } catch (e: SerializationException) {
            return Result.failure(MeridianWireRefusal(malformed("not a meridian/tools/1 request: ${firstLine(e.message)}")))
        } catch (e: IllegalArgumentException) {
            return Result.failure(MeridianWireRefusal(malformed("not a meridian/tools/1 request: ${firstLine(e.message)}")))
        }
        if (request.protocol != null && request.protocol != PROTOCOL) {
            return Result.failure(MeridianWireRefusal(malformed("unsupported protocol ${request.protocol}; this host speaks $PROTOCOL")))
        }
        if (request.argv.isEmpty()) {
            return Result.failure(MeridianWireRefusal(malformed("argv is empty")))
        }
        return Result.success(request)
    }

    fun encodeRequest(request: MeridianToolsWireRequest): String =
        json.encodeToString(MeridianToolsWireRequest.serializer(), request)

    fun decodeResponse(line: String): MeridianToolsWireResponse =
        json.decodeFromString(MeridianToolsWireResponse.serializer(), line)

    /** One response line, without the terminating newline. */
    fun encodeResponse(response: MeridianResponse): String = json.encodeToString(
        MeridianToolsWireResponse.serializer(),
        MeridianToolsWireResponse(response.exitCode, response.stdout, response.stderr),
    )

    private fun malformed(message: String): MeridianResponse =
        MeridianError(MeridianErrorCode.USAGE, message, hint = "The meridian shim and this host disagree on the wire; reinstall the shim.").toResponse()

    private fun firstLine(message: String?): String = message?.lineSequence()?.firstOrNull().orEmpty()
}

/**
 * One call: the shim's argv (program name first or not; the router strips it), the stdin JSON when
 * stdin was not a terminal, and the scope the shim read from `LETTA_AGENT_ID` /
 * `LETTA_CONVERSATION_ID`. That scope is a claim, not an identity: [MeridianCallerBinder] decides
 * what it is worth. [token] is the bearer token of the loopback TCP fallback, unused on the socket.
 */
@Serializable
data class MeridianToolsWireRequest(
    val argv: List<String>,
    val stdin: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val token: String? = null,
    val protocol: String? = null,
)

/** The answer the shim prints: [stdout] to stdout, [stderr] to stderr, and exits with [exitCode]. */
@Serializable
data class MeridianToolsWireResponse(
    @SerialName("exit_code") val exitCode: Int,
    val stdout: String,
    val stderr: String = "",
)

/** A request line the endpoint refuses before it reaches the router; [response] is the answer. */
class MeridianWireRefusal(val response: MeridianResponse) : Exception(response.stdout)
