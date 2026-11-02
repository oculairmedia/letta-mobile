package com.letta.mobile.data.controller.node.iroh.records

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

internal fun invalid(field: String): Nothing = throw IllegalArgumentException("validation.invalid_params: $field")
internal fun conflict(field: String): Nothing = throw IllegalArgumentException("validation.conflict: $field")
internal fun capacity(field: String): Nothing = error("storage.capacity: $field")

internal fun bounded(value: String, field: String, max: Int): String {
    if (value.isBlank() || value.length > max) invalid(field)
    return value
}

internal fun JsonObject?.text(field: String, max: Int, optional: Boolean = false): String? {
    val raw = this?.get(field)
    if ((raw == null || raw == JsonNull) && optional) return null
    val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid(field)
    return bounded(value, field, max)
}

internal fun JsonObject?.integer(field: String, default: Int, max: Int = Int.MAX_VALUE, min: Int = 0): Int {
    val raw = this?.get(field) ?: return default
    val number = (raw as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: invalid(field)
    if (number !in min..max) invalid(field)
    return number
}

internal fun JsonObject?.instant(field: String, optional: Boolean = false): Long? {
    val value = text(field, MAX_ID_LENGTH, optional) ?: return null
    val parsed = runCatching { Instant.parse(value).toEpochMilli() }.getOrElse { invalid(field) }
    if (parsed !in earliestInstant..latestQueryInstant) invalid(field)
    return parsed
}

internal fun saneInstant(value: Long, now: Long, field: String) {
    if (value < earliestInstant || value > now + FUTURE_DAYS * DAY_MS) invalid(field)
}

// The native cron run-log reader uses ECMAScript String.trim before validating its file key.
private const val CRON_TRIM_CHARS = "\t\n\u000b\u000c\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004" +
    "\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"

internal fun safeCronReference(value: String, field: String) {
    bounded(value, field, MAX_ID_LENGTH)
    val safe = value.trim { it in CRON_TRIM_CHARS }
    if (safe.isEmpty() || safe.any { it == '/' || it == '\\' || it == '\u0000' }) invalid(field)
}

internal fun RecordedRunInput.validate(now: Long) {
    bounded(agentId, "agentId", MAX_ID_LENGTH)
    scheduleId?.let { safeCronReference(it, "scheduleId") }
    bounded(scheduleName, "scheduleName", MAX_LABEL_LENGTH)
    bounded(reason, "reason", MAX_REASON_LENGTH)
    detail?.let { bounded(it, "detail", MAX_DETAIL_LENGTH) }
    saneInstant(instantMs, now, "instant")
}

internal fun RecordedLeaseInput.validate(now: Long) {
    bounded(id, "id", MAX_ID_LENGTH)
    bounded(owner, "owner", MAX_ID_LENGTH)
    bounded(label, "label", MAX_LABEL_LENGTH)
    runId?.let { bounded(it, "runId", MAX_ID_LENGTH) }
    detail?.let { bounded(it, "detail", MAX_DETAIL_LENGTH) }
    saneInstant(startedAtMs, now, "startedAt")
    saneInstant(dueAtMs, now, "dueAt")
    if (dueAtMs < startedAtMs) invalid("dueAt")
}

internal fun parseRun(params: JsonObject?) = RecordedRunInput(
    agentId = params.text("agentId", MAX_ID_LENGTH)!!,
    scheduleId = params.text("scheduleId", MAX_ID_LENGTH, optional = true),
    scheduleName = params.text("scheduleName", MAX_LABEL_LENGTH)!!,
    instantMs = params.instant("instant")!!,
    status = runStatus(params.text("status", MAX_ID_LENGTH)!!),
    reason = params.text("reason", MAX_REASON_LENGTH)!!,
    source = runSource(params.text("source", MAX_ID_LENGTH)!!),
    detail = params.text("detail", MAX_DETAIL_LENGTH, optional = true),
)

internal fun parseLease(params: JsonObject?) = RecordedLeaseInput(
    id = params.text("id", MAX_ID_LENGTH)!!,
    owner = params.text("owner", MAX_ID_LENGTH)!!,
    label = params.text("label", MAX_LABEL_LENGTH)!!,
    startedAtMs = params.instant("startedAt")!!,
    dueAtMs = params.instant("dueAt")!!,
    state = leaseState(params.text("state", MAX_ID_LENGTH)!!),
    runId = params.text("runId", MAX_ID_LENGTH, optional = true),
    detail = params.text("detail", MAX_DETAIL_LENGTH, optional = true),
)

private fun runStatus(value: String): RecordedRunStatus =
    RecordedRunStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RecordedRunStatus.UNKNOWN

private fun runSource(value: String): RecordedRunSource =
    RecordedRunSource.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RecordedRunSource.UNKNOWN

internal fun leaseState(value: String): RecordedLeaseState =
    RecordedLeaseState.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: RecordedLeaseState.UNKNOWN
