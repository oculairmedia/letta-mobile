package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** The per-conversation sidecar maps joined into each projected message. */
internal data class MessageSidecars(
    val realTimes: Map<String, String>,
    val otid: Map<String, String>,
    val attachments: Map<String, JsonArray>,
    val runIds: Map<String, String>,
)

/**
 * lgns8.9 slice 3: the `translate.ts:localMessageToConversationMessages` 1:N
 * fan-out. One LocalMessage projects to one or more already-projected wire
 * messages; wire field sets and key order match the TS object literals
 * byte-for-byte. Split out of [LocalBackendMessageReader] as pure code motion —
 * no behavior change.
 */
internal class LocalBackendMessageProjection(private val support: LocalBackendStoreSupport) {

    private val typeOffsetMs = mapOf(
        "user_message" to 0L,
        "system_message" to 0L,
        "reasoning_message" to 10L,
        "tool_call_message" to 20L,
        "tool_return_message" to 30L,
        "assistant_message" to 40L,
    )

    /**
     * Faithful port of translate.ts `localMessageToConversationMessages`.
     * One LocalMessage projects to one or more wire messages. Wire field sets
     * and key order match the TS object literals byte-for-byte.
     */
    fun localMessageToConversationMessages(
        localMsg: JsonObject,
        sidecars: MessageSidecars,
    ): List<JsonObject> {
        val id = localMsg["id"]?.stringOrNull()
        val sentinel = (localMsg["metadata"] as? JsonObject)?.get("created_at")?.stringOrNull()
        val real = id?.let { sidecars.realTimes[it] }
        val created = real ?: sentinel ?: support.isoMillis(System.currentTimeMillis())
        val role = localMsg["role"]?.stringOrNull() ?: "system"
        val parts = localMsg["parts"] as? JsonArray ?: JsonArray(emptyList())
        val ctx = ProjCtx(
            id = id,
            created = created,
            projectedOtid = (id?.let { sidecars.otid[it] }) ?: id,
            projectedRunId = (id?.let { sidecars.runIds[it] })?.let { JsonPrimitive(it) } ?: JsonNull,
            attachmentRefs = id?.let { sidecars.attachments[it] },
        )
        return when {
            role == "user" || role == "system" -> projectUserOrSystem(ctx, role, parts)
            role == "toolResult" -> projectToolResultRow(ctx, localMsg, parts)
            else -> projectAssistantParts(ctx, parts)
        }
    }

    /** Shared per-message projection context computed once in [localMessageToConversationMessages]. */
    private class ProjCtx(
        val id: String?,
        val created: String,
        val projectedOtid: String?,
        val projectedRunId: JsonElement,
        val attachmentRefs: JsonArray?,
    )

    /** Port of translate.ts withTypeOffset: add the per-type ms offset to the ISO date. */
    private fun withTypeOffset(createdIso: String, messageType: String): String {
        val off = typeOffsetMs[messageType] ?: 0L
        if (off == 0L) return createdIso
        val t = runCatching { Instant.parse(createdIso).toEpochMilli() }.getOrNull() ?: return createdIso
        return support.isoMillis(t + off)
    }

    /** Port of translate.ts partsToText: concatenate all `text` parts. */
    private fun partsToText(parts: JsonArray): String {
        val sb = StringBuilder()
        for (p in parts) {
            val o = p as? JsonObject ?: continue
            if (o["type"]?.stringOrNull() == "text") sb.append(o["text"]?.stringOrNull() ?: "")
        }
        return sb.toString()
    }

    /** Port of translate.ts stripSystemReminders (user-role only). */
    private fun stripSystemReminders(text: String): String =
        text
            .replace(Regex("<system-reminder>[\\s\\S]*?</system-reminder>"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** Port of translate.ts flattenToolOutput. */
    private fun flattenToolOutput(value: JsonElement?): String = when (value) {
        null, is JsonNull -> ""
        is JsonPrimitive -> if (value.isString) value.content else value.toString()
        is JsonArray -> value.joinToString("") { p ->
            when {
                p is JsonPrimitive && p.isString -> p.content
                p is JsonObject && p["type"]?.stringOrNull() == "text" && p["text"]?.stringOrNull() != null ->
                    p["text"]!!.stringOrNull()!!
                else -> p.toString()
            }
        }
        is JsonObject -> value.toString()
    }

    /** JS `JSON.stringify(value ?? {})` — compact; string args pass through verbatim. */
    private fun jsonStringifyArgs(value: JsonElement?): String = when (value) {
        null, is JsonNull -> "{}"
        is JsonPrimitive -> if (value.isString) value.content else value.toString()
        else -> value.toString()
    }

    private fun toStringArrayOrNull(value: JsonElement?): JsonElement = when (value) {
        null, is JsonNull -> JsonNull
        is JsonArray -> JsonArray(value.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { JsonPrimitive(it.content) })
        is JsonPrimitive -> if (value.isString) JsonArray(listOf(JsonPrimitive(value.content))) else JsonNull
        else -> JsonNull
    }

    /** tool_call sub-object shared by the tool-call projections. */
    private fun toolCallInner(name: String, argsStr: String, callId: String): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", argsStr)
        put("tool_call_id", callId)
    }

    /** tool_returns[] entry shared by the tool-return projections. */
    private fun toolReturnInner(
        callId: String,
        status: String,
        stdout: JsonElement,
        stderr: JsonElement,
        returnText: String,
    ): JsonObject = buildJsonObject {
        put("tool_call_id", callId)
        put("status", status)
        put("stdout", stdout)
        put("stderr", stderr)
        put("func_response", returnText)
        put("type", "tool")
    }

    /** User / system: collapse text parts into one wire message. */
    private fun projectUserOrSystem(ctx: ProjCtx, role: String, parts: JsonArray): List<JsonObject> {
        var text = partsToText(parts)
        if (role == "user") text = stripSystemReminders(text)
        if (text.isEmpty()) return emptyList()
        val wireType = if (role == "user") "user_message" else "system_message"
        val wire = buildJsonObject {
            put("id", ctx.id ?: "")
            put("date", withTypeOffset(ctx.created, wireType))
            put("name", JsonNull)
            put("message_type", wireType)
            put("otid", ctx.projectedOtid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("content", text)
            // attachRefsToWireMessage: only user_message, only when refs present.
            if (wireType == "user_message" && ctx.attachmentRefs != null && ctx.attachmentRefs.isNotEmpty()) {
                put("attachments", ctx.attachmentRefs)
            }
        }
        return listOf(wire)
    }

    /** True only for a non-string primitive whose content is exactly `true` (JS `=== true` on a bool). */
    private fun parseBooleanFlag(value: JsonElement?): Boolean =
        (value as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

    /** toolResult top-level row (letta-code 0.25.x). */
    private fun projectToolResultRow(ctx: ProjCtx, localMsg: JsonObject, parts: JsonArray): List<JsonObject> {
        val callId = localMsg["toolCallId"]?.stringOrNull() ?: ""
        val toolName = localMsg["toolName"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
        val isError = parseBooleanFlag(localMsg["isError"])
        val returnText = partsToText(parts)
        val status = if (isError) "error" else "success"
        val tr = toolReturnInner(callId, status, JsonNull, JsonNull, returnText)
        val trMsg = buildJsonObject {
            put("id", if (callId.isNotEmpty()) "toolreturn-$callId" else (ctx.id ?: ""))
            put("date", withTypeOffset(ctx.created, "tool_return_message"))
            put("name", toolName?.let { JsonPrimitive(it) } ?: JsonNull)
            put("message_type", "tool_return_message")
            put("otid", ctx.projectedOtid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", if (isError) JsonPrimitive(true) else JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("tool_call_id", callId)
            put("status", status)
            put("tool_return", returnText)
            put("stdout", JsonNull)
            put("stderr", JsonNull)
            put("tool_returns", JsonArray(listOf(tr)))
        }
        return listOf(trMsg)
    }

    /** Groups consecutive `text` parts into a single assistant run, flushed on demand. */
    private class TextAccumulator {
        private val sb = StringBuilder()
        private var startIndex = -1
        fun append(index: Int, text: String) {
            if (startIndex == -1) startIndex = index
            sb.append(text)
        }
        /** Returns (startIndex, text) and resets, or null when nothing is pending. */
        fun take(): Pair<Int, String>? {
            if (sb.isEmpty()) return null
            val run = startIndex to sb.toString()
            sb.setLength(0)
            startIndex = -1
            return run
        }
    }

    /** Emit the pending grouped-text run (if any) as an assistant_message before a tool part. */
    private fun flushPendingText(out: MutableList<JsonObject>, ctx: ProjCtx, acc: TextAccumulator) {
        val run = acc.take() ?: return
        out += buildAssistantText(ctx, out.isEmpty(), run.first, run.second)
    }

    /**
     * Dispatch a single non-text part to its wire projection, or null when the part is
     * not a recognized tool-shaped part (shim logs + skips). Check order matches the
     * original if-chain byte-for-byte.
     */
    private fun projectAssistantPart(ctx: ProjCtx, i: Int, part: JsonObject, type: String): List<JsonObject>? = when {
        type == "reasoning" && part["text"]?.stringOrNull() != null -> listOf(buildReasoning(ctx, i, part))
        // Legacy `tool-call` + new camelCase `toolCall`.
        type == "tool-call" || type == "toolCall" -> listOf(buildToolCall(ctx, i, part))
        // Native LocalBackend tool part: `tool-<name>` with toolCallId.
        type.startsWith("tool-") && type != "tool-call" && type != "tool-return" &&
            part["toolCallId"]?.stringOrNull() != null -> projectNativeToolPart(ctx, type, part)
        type == "tool-return" -> listOf(buildToolReturnPart(ctx, i, part))
        else -> null
    }

    /** Assistant / tool: walk parts, grouping consecutive text, dispatching each tool-shaped part. */
    private fun projectAssistantParts(ctx: ProjCtx, parts: JsonArray): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        val acc = TextAccumulator()
        for (i in 0 until parts.size) {
            val part = parts[i] as? JsonObject ?: continue
            val type = part["type"]?.stringOrNull() ?: continue
            val text = if (type == "text") part["text"]?.stringOrNull() else null
            if (text != null) {
                acc.append(i, text)
                continue
            }
            val projected = projectAssistantPart(ctx, i, part, type) ?: continue
            flushPendingText(out, ctx, acc)
            out += projected
        }
        flushPendingText(out, ctx, acc)
        return out
    }

    /** assistant_message wire object for a run of grouped text parts. */
    private fun buildAssistantText(ctx: ProjCtx, isFirst: Boolean, startIndex: Int, text: String): JsonObject =
        buildJsonObject {
            put("id", if (isFirst) (ctx.id ?: "") else "${ctx.id}:assistant:$startIndex")
            put("date", withTypeOffset(ctx.created, "assistant_message"))
            put("name", JsonNull)
            put("message_type", "assistant_message")
            put("otid", ctx.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("content", text)
        }

    /** reasoning_message wire object for a `reasoning` part. */
    private fun buildReasoning(ctx: ProjCtx, i: Int, part: JsonObject): JsonObject {
        val signature = (part["providerMetadata"] as? JsonObject)?.get("signature")?.stringOrNull()
        return buildJsonObject {
            put("id", "${ctx.id}:reasoning:$i")
            put("date", withTypeOffset(ctx.created, "reasoning_message"))
            put("name", JsonNull)
            put("message_type", "reasoning_message")
            put("otid", ctx.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("source", "reasoner_model")
            put("reasoning", part["text"]!!.stringOrNull()!!)
            put("signature", signature?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    /** tool_call_message wire object for a legacy `tool-call` / camelCase `toolCall` part. */
    private fun buildToolCall(ctx: ProjCtx, i: Int, part: JsonObject): JsonObject {
        val argsStr = jsonStringifyArgs(part["arguments"])
        val callId = part["toolCallId"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
            ?: part["id"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
            ?: ""
        val name = part["name"]?.stringOrNull()?.takeIf { it.isNotEmpty() } ?: "tool"
        val tc = toolCallInner(name, argsStr, callId)
        return buildJsonObject {
            put("id", if (callId.isNotEmpty()) "toolcall-$callId" else "${ctx.id}:tool:$i:call")
            put("date", withTypeOffset(ctx.created, "tool_call_message"))
            put("name", name)
            put("message_type", "tool_call_message")
            put("otid", ctx.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("tool_call", tc)
            put("tool_calls", JsonArray(listOf(tc)))
        }
    }

    /** Native `tool-<name>` part: a tool_call_message plus an optional tool_return_message. */
    private fun projectNativeToolPart(ctx: ProjCtx, type: String, part: JsonObject): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        val toolCallId = part["toolCallId"]!!.stringOrNull()!!
        val toolName = type.substring("tool-".length)
        val argsStr = jsonStringifyArgs(part["input"])
        out += buildNativeToolCallMessage(ctx, toolName, toolCallId, argsStr)
        val state = part["state"]?.stringOrNull()
        if (state == "output-available" || state == "output-error" || state == "output-denied") {
            out += buildNativeToolReturnMessage(ctx, toolName, toolCallId, state, part)
        }
        return out
    }

    /** tool_call_message wire object for a native `tool-<name>` part. */
    private fun buildNativeToolCallMessage(
        ctx: ProjCtx,
        toolName: String,
        toolCallId: String,
        argsStr: String,
    ): JsonObject {
        val tc = toolCallInner(toolName, argsStr, toolCallId)
        return buildJsonObject {
            put("id", "toolcall-$toolCallId")
            put("date", withTypeOffset(ctx.created, "tool_call_message"))
            put("name", toolName)
            put("message_type", "tool_call_message")
            put("otid", ctx.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("tool_call", tc)
            put("tool_calls", JsonArray(listOf(tc)))
        }
    }

    /** tool_return_message wire object for a completed native `tool-<name>` part. */
    private fun buildNativeToolReturnMessage(
        ctx: ProjCtx,
        toolName: String,
        toolCallId: String,
        state: String,
        part: JsonObject,
    ): JsonObject {
        val isError = state != "output-available"
        val returnText = if (isError) {
            part["errorText"]?.stringOrNull() ?: flattenToolOutput(part["output"])
        } else {
            flattenToolOutput(part["output"])
        }
        val status = if (isError) "error" else "success"
        val tr = toolReturnInner(toolCallId, status, JsonNull, JsonNull, returnText)
        return buildJsonObject {
            put("id", "toolreturn-$toolCallId")
            put("date", withTypeOffset(ctx.created, "tool_return_message"))
            put("name", toolName)
            put("message_type", "tool_return_message")
            put("otid", ctx.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", if (isError) JsonPrimitive(true) else JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("tool_call_id", toolCallId)
            put("status", status)
            put("tool_return", returnText)
            put("stdout", JsonNull)
            put("stderr", JsonNull)
            put("tool_returns", JsonArray(listOf(tr)))
        }
    }

    /** Resolve the `tool_return` raw value to its wire text: verbatim string, `"\"\""` for null, else JSON. */
    private fun resolveToolReturnText(returnRaw: JsonElement?): String = when {
        returnRaw is JsonPrimitive && returnRaw.isString -> returnRaw.content
        returnRaw == null || returnRaw is JsonNull -> "\"\""
        else -> returnRaw.toString()
    }

    /** tool_return_message wire object for an explicit `tool-return` part. */
    private fun buildToolReturnPart(ctx: ProjCtx, i: Int, part: JsonObject): JsonObject {
        val callId = part["toolCallId"]?.stringOrNull() ?: ""
        val isError = part["status"]?.stringOrNull() == "error"
        val status = if (isError) "error" else "success"
        val returnText = resolveToolReturnText(part["tool_return"])
        val stdout = toStringArrayOrNull(part["stdout"])
        val stderr = toStringArrayOrNull(part["stderr"])
        val name = part["name"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
        val tr = toolReturnInner(callId, status, stdout, stderr, returnText)
        return buildJsonObject {
            put("id", if (callId.isNotEmpty()) "toolreturn-$callId" else "${ctx.id}:tool:$i:return")
            put("date", withTypeOffset(ctx.created, "tool_return_message"))
            put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
            put("message_type", "tool_return_message")
            put("otid", ctx.id?.let { JsonPrimitive(it) } ?: JsonNull)
            put("sender_id", JsonNull)
            put("step_id", JsonNull)
            put("is_err", if (isError) JsonPrimitive(true) else JsonNull)
            put("seq_id", JsonNull)
            put("run_id", ctx.projectedRunId)
            put("tool_call_id", callId)
            put("status", status)
            put("tool_return", returnText)
            put("stdout", stdout)
            put("stderr", stderr)
            put("tool_returns", JsonArray(listOf(tr)))
        }
    }
}
