package com.letta.mobile.data.controller.node.iroh.records

import com.letta.mobile.data.controller.node.iroh.errors.AdminRpcClientError
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal const val MAX_ID_LENGTH = 256
internal const val MAX_LABEL_LENGTH = 512
internal const val MAX_REASON_LENGTH = 2048
internal const val MAX_DETAIL_LENGTH = 8192
internal const val MAX_PAGE_LIMIT = 200
internal const val DEFAULT_PAGE_LIMIT = 50
internal const val MAX_RECORD_BYTES = 65536
internal const val DAY_MS = 86_400_000L
private const val FUTURE_DAYS = 366L
private val latestQueryInstant = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli()
private val earliestInstant = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()

internal fun String.invalid(): Nothing = throw AdminRpcClientError("validation.invalid_params: $this")
internal fun String.conflict(): Nothing = throw AdminRpcClientError("validation.conflict: $this")
internal fun String.capacity(): Nothing = throw AdminRpcClientError("storage.capacity: $this")

internal data class TextField(val name: String, val maxLength: Int, val optional: Boolean = false)

private fun String.bounded(field: TextField): String {
    if (isBlank() || length > field.maxLength) field.name.invalid()
    return this
}

private fun isMissing(raw: kotlinx.serialization.json.JsonElement?): Boolean = raw == null || raw == JsonNull

internal fun JsonObject?.text(field: TextField): String? {
    val raw = this?.get(field.name)
    if (field.optional && isMissing(raw)) return null
    val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: field.name.invalid()
    return value.bounded(field)
}

private data class IntegerField(val name: String, val default: Int, val range: IntRange)

internal fun JsonObject?.integer(field: String, default: Int, max: Int = Int.MAX_VALUE, min: Int = 0): Int =
    integer(IntegerField(field, default, min..max))

private fun JsonObject?.integer(field: IntegerField): Int {
    val raw = this?.get(field.name) ?: return field.default
    val number = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: field.name.invalid()
    if (number !in field.range) field.name.invalid()
    return number
}

internal data class InstantField(val name: String, val optional: Boolean = false)

internal fun JsonObject?.instant(field: InstantField): Long? {
    val value = text(TextField(field.name, MAX_ID_LENGTH, field.optional)) ?: return null
    val parsed = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { field.name.invalid() }
    if (parsed !in earliestInstant..latestQueryInstant) field.name.invalid()
    return parsed
}

internal data class InstantCheck(val value: Long, val now: Long, val field: String) {
    fun validate() {
        if (value < earliestInstant || value > now + FUTURE_DAYS * DAY_MS) field.invalid()
    }
}

// The native cron run-log reader uses ECMAScript String.trim before validating its file key.
private const val CRON_TRIM_CHARS = "\t\n\u000b\u000c\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004" +
    "\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"

internal data class CronReference(val value: String, val field: String) {
    fun validate() {
        value.bounded(TextField(field, MAX_ID_LENGTH))
        val safe = value.trim { it in CRON_TRIM_CHARS }
        if (safe.isEmpty() || safe.any { it.isUnsafeCronCharacter() }) field.invalid()
    }
}

private fun Char.isUnsafeCronCharacter(): Boolean = this in "/\\\u0000"

internal data class ValidationClock(val now: Long)

internal fun RecordedRunInput.validate(clock: ValidationClock) {
    agentId.bounded(TextField("agentId", MAX_ID_LENGTH))
    scheduleId?.let { CronReference(it, "scheduleId").validate() }
    scheduleName.bounded(TextField("scheduleName", MAX_LABEL_LENGTH))
    reason.bounded(TextField("reason", MAX_REASON_LENGTH))
    detail?.let { it.bounded(TextField("detail", MAX_DETAIL_LENGTH)) }
    InstantCheck(instantMs, clock.now, "instant").validate()
}

internal fun RecordedLeaseInput.validate(clock: ValidationClock) {
    id.bounded(TextField("id", MAX_ID_LENGTH))
    owner.bounded(TextField("owner", MAX_ID_LENGTH))
    label.bounded(TextField("label", MAX_LABEL_LENGTH))
    runId?.let { it.bounded(TextField("runId", MAX_ID_LENGTH)) }
    detail?.let { it.bounded(TextField("detail", MAX_DETAIL_LENGTH)) }
    InstantCheck(startedAtMs, clock.now, "startedAt").validate()
    InstantCheck(dueAtMs, clock.now, "dueAt").validate()
    if (dueAtMs < startedAtMs) "dueAt".invalid()
}

internal fun parseRun(params: JsonObject?) = RecordedRunInput(
    agentId = params.text(TextField("agentId", MAX_ID_LENGTH))!!,
    scheduleId = params.text(TextField("scheduleId", MAX_ID_LENGTH, optional = true)),
    scheduleName = params.text(TextField("scheduleName", MAX_LABEL_LENGTH))!!,
    instantMs = params.instant(InstantField("instant"))!!,
    status = params.text(TextField("status", MAX_ID_LENGTH))!!.runStatus(),
    reason = params.text(TextField("reason", MAX_REASON_LENGTH))!!,
    source = params.text(TextField("source", MAX_ID_LENGTH))!!.runSource(),
    detail = params.text(TextField("detail", MAX_DETAIL_LENGTH, optional = true)),
)

internal fun parseLease(params: JsonObject?) = RecordedLeaseInput(
    id = params.text(TextField("id", MAX_ID_LENGTH))!!,
    owner = params.text(TextField("owner", MAX_ID_LENGTH))!!,
    label = params.text(TextField("label", MAX_LABEL_LENGTH))!!,
    startedAtMs = params.instant(InstantField("startedAt"))!!,
    dueAtMs = params.instant(InstantField("dueAt"))!!,
    state = params.text(TextField("state", MAX_ID_LENGTH))!!.leaseState(),
    runId = params.text(TextField("runId", MAX_ID_LENGTH, optional = true)),
    detail = params.text(TextField("detail", MAX_DETAIL_LENGTH, optional = true)),
)

private fun String.runStatus(): RecordedRunStatus =
    RecordedRunStatus.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: RecordedRunStatus.UNKNOWN

private fun String.runSource(): RecordedRunSource =
    RecordedRunSource.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: RecordedRunSource.UNKNOWN

internal fun String.leaseState(): RecordedLeaseState =
    RecordedLeaseState.entries.firstOrNull { it.name.equals(this, ignoreCase = true) } ?: RecordedLeaseState.UNKNOWN
