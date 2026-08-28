package com.letta.mobile.data.transport.appserver

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

object AppServerProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** Placeholder substituted for credential values in redacted diagnostics. */
    const val REDACTED_PLACEHOLDER: String = "<redacted>"

    /** Upper bound on decode-failure diagnostic length so logs/traces stay bounded. */
    const val MAX_DIAGNOSTIC_LENGTH: Int = 512

    /**
     * Upstream stream-channel message types (Letta Code `STREAM_CHANNEL_MESSAGE_TYPES`).
     * On one-socket transports these are demuxed into [AppServerChannel.Stream];
     * everything else (including malformed JSON) lands on [AppServerChannel.Control].
     */
    private val STREAM_CHANNEL_MESSAGE_TYPES: Set<String> = setOf(
        "stream_delta",
        "update_device_status",
        "update_loop_status",
        "update_queue",
        "update_subagent_state",
    )

    private val KNOWN_INBOUND_MESSAGE_TYPES: Set<String> = STREAM_CHANNEL_MESSAGE_TYPES + setOf(
        "auth_response",
        "runtime_start_response",
        "sync_response",
        "abort_message_response",
        "external_tool_call_request",
        "control_request",
        "admin_rpc_response",
        "list_models_response",
        "skill_enable_response",
        "skill_disable_response",
        "skills_updated",
        "cron_list_response",
        "cron_add_response",
        "cron_get_response",
        "cron_runs_response",
        "cron_trigger_response",
        "cron_update_response",
        "cron_delete_response",
        "cron_delete_all_response",
        "write_memory_file_response",
        "get_reflection_settings_response",
        "set_reflection_settings_response",
        "get_cwd_map_response",
        "agent_list_response",
        "agent_retrieve_response",
        "agent_create_response",
        "agent_update_response",
        "agent_delete_response",
        "conversation_list_response",
        "conversation_retrieve_response",
        "conversation_create_response",
        "conversation_update_response",
        "conversation_messages_list_response",
        "conversation_compact_response",
        "channels_list_response",
        "channel_accounts_list_response",
        "channel_start_response",
        "channel_account_update_response",
        "app_server_info_response",
    )

    private val redactedPrimitive = JsonPrimitive(REDACTED_PLACEHOLDER)
    private val emptyRaw = JsonObject(emptyMap())

    private class ParsedInboundFrame(
        val type: JsonPrimitive?,
        val raw: JsonObject,
    ) {
        val typeName: String?
            get() = type?.contentOrNull
    }

    fun encodeCommand(command: AppServerCommand): String =
        json.encodeToString(AppServerCommand.serializer(), command)

    /**
     * Decode one inbound App Server frame. This is **total** — it never throws.
     * When [channel] is absent, the one-socket transport channel is inferred from
     * the parsed top-level message type without parsing the frame a second time.
     *
     * Forward-compatibility contract (letta-mobile-lgns8.4):
     * - Unknown top-level `type` values decode to [AppServerInboundFrame.Unknown]
     *   with the raw envelope preserved, so receive loops survive new server frames.
     * - Additive object keys on known frames are ignored ([Json.ignoreUnknownKeys]).
     * - A malformed *known* frame (missing/mistyped required field) or non-object /
     *   syntactically invalid JSON becomes an explicit
     *   [AppServerInboundFrame.DecodeFailure] carrying the preserved raw envelope
     *   (when available) plus a bounded, credential-redacted diagnostic — instead of
     *   throwing and killing the receive loop.
     */
    fun decodeFrame(rawJson: String, channel: AppServerChannel? = null): AppServerReceivedFrame {
        val element = runCatching { json.parseToJsonElement(rawJson) }.getOrNull()
        val raw = element as? JsonObject
        if (raw == null) {
            return malformedFrame(element, channel)
        }
        val parsed = ParsedInboundFrame(type = raw["type"] as? JsonPrimitive, raw = raw)
        val resolvedChannel = channel ?: if (parsed.typeName in STREAM_CHANNEL_MESSAGE_TYPES) {
            AppServerChannel.Stream
        } else {
            AppServerChannel.Control
        }
        return AppServerReceivedFrame(
            channel = resolvedChannel,
            frame = decodeInboundFrame(parsed),
            raw = raw,
        )
    }

    private fun malformedFrame(
        element: JsonElement?,
        channel: AppServerChannel?,
    ): AppServerReceivedFrame {
        val reason = if (element == null) "invalid JSON syntax" else "top-level frame is not a JSON object"
        return AppServerReceivedFrame(
            channel = channel ?: AppServerChannel.Control,
            frame = AppServerInboundFrame.DecodeFailure(
                declaredType = null,
                raw = null,
                diagnostic = boundedDiagnostic("decode_failure: $reason"),
            ),
            raw = emptyRaw,
        )
    }

    private fun decodeInboundFrame(parsed: ParsedInboundFrame): AppServerInboundFrame {
        if (parsed.typeName !in KNOWN_INBOUND_MESSAGE_TYPES) {
            return AppServerInboundFrame.Unknown(type = parsed.typeName, raw = parsed.raw)
        }
        // Unknown protocol fields are preserved on AppServerReceivedFrame.raw; typed
        // frames intentionally model only what upstream actually sends.
        return decodeKnown(parsed) {
            json.decodeFromJsonElement<AppServerInboundFrame>(parsed.raw)
        }
    }

    private inline fun decodeKnown(
        parsed: ParsedInboundFrame,
        decode: () -> AppServerInboundFrame,
    ): AppServerInboundFrame =
        runCatching { decode() }.getOrElse { error ->
            val reason = error.message ?: error::class.simpleName ?: "decode failed"
            AppServerInboundFrame.DecodeFailure(
                declaredType = parsed.typeName,
                raw = parsed.raw,
                diagnostic = boundedDiagnostic("decode_failure type=${parsed.typeName}: $reason"),
            )
        }

    /**
     * Cap a decode-failure diagnostic to [MAX_DIAGNOSTIC_LENGTH]. Callers compose
     * the message; it must intentionally exclude the raw frame payload (available
     * separately on [AppServerInboundFrame.DecodeFailure.raw]) so nothing that
     * leaves the process — logs, traces, fanout — carries frame contents, and the
     * cap prevents a hostile/oversized frame from bloating sinks.
     */
    fun boundedDiagnostic(message: String): String =
        if (message.length > MAX_DIAGNOSTIC_LENGTH) {
            message.take(MAX_DIAGNOSTIC_LENGTH - 1) + "\u2026"
        } else {
            message
        }

    /**
     * Recursively replace credential-bearing values with [REDACTED_PLACEHOLDER].
     * Field matching mirrors the contract-baseline hygiene predicate so runtime
     * redaction and committed-fixture redaction stay consistent.
     */
    fun redactCredentials(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (key, value) ->
                if (isCredentialField(key)) redactedPrimitive else redactCredentials(value)
            },
        )
        is JsonArray -> JsonArray(element.map(::redactCredentials))
        else -> element
    }

    fun isCredentialField(name: String): Boolean {
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        return normalized == "authorization" ||
            normalized == "password" ||
            normalized == "privatekey" ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("apikey")
    }
}
