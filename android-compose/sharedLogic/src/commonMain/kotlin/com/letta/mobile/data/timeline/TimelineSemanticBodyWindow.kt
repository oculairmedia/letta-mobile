package com.letta.mobile.data.timeline

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Plain semantic text only: never treat serialized event slices as displayable content. */
enum class TimelineSemanticField(val wireName: String) { Content("content"), ToolReturn("toolReturnContent") }

class TimelineSemanticBudget(
    val maxInputBytes: Long = 2L * 1024 * 1024,
    val maxOutputBytes: Int = 16 * 1024,
    val chunkBytes: Int = 16 * 1024,
    val maxReads: Int = 128,
) {
    init {
        require(maxInputBytes in 1..64L * 1024 * 1024)
        require(maxOutputBytes in 4..64 * 1024)
        require(chunkBytes in 1..64 * 1024)
        require(maxReads in 1..128)
    }
}

sealed interface TimelineSemanticWindowResult {
    data class Text(
        val value: String,
        val nextScalarOffset: Long?,
        val inputBytes: Long,
        val outputBytes: Int,
        val reads: Int,
    ) : TimelineSemanticWindowResult
    data class Deferred(val reason: Reason) : TimelineSemanticWindowResult
    enum class Reason { UnsupportedType, InputBudget, ReadBudget, Malformed, MissingField, StructuralLimit }
}

/**
 * Explicit user-triggered expansion only; never call during initial page projection.
 * Each request rescans one revision-pinned event, not history. maxReads caps callback operations,
 * each of which currently does a metadata lookup; this is not proof of aggregate DB metadata bounds.
 * Input/output/read limits are cumulative within one request, not across repeated user actions. Input work, chunk residency and
 * output are independently capped. Working storage is at most two input chunks during refill,
 * one output builder plus its returned string (each <= 2 * maxOutputBytes UTF-16 bytes),
 * and depth-32 structural/key state (keys <= 128 UTF-16 units). No cursor retains prior text.
 * Hosts must replace, not append,
 * windows and run this on their injected decode dispatcher. Only top-level content/tool return
 * strings are supported; tool arguments, attachments and opaque schemas remain explicitly deferred.
 */
object TimelineSemanticBodyWindow {
    suspend fun read(
        reference: TimelineBodyReference,
        field: TimelineSemanticField,
        scalarOffset: Long = 0,
        budget: TimelineSemanticBudget = TimelineSemanticBudget(),
        readChunk: suspend (Long, Int) -> TimelineBodyChunk,
    ): TimelineSemanticWindowResult {
        require(scalarOffset >= 0)
        if (reference.contentType != TIMELINE_EVENT_CONTENT_TYPE) {
            return TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.UnsupportedType)
        }
        if (reference.pointer.encodedBytes > budget.maxInputBytes) {
            return TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.InputBudget)
        }
        if (reference.pointer.encodedBytes > budget.chunkBytes.toLong() * budget.maxReads) {
            return TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.ReadBudget)
        }
        val parser = Parser(reference, field, scalarOffset, budget, readChunk)
        return try {
            parser.parse()
        } catch (failure: Invalid) {
            TimelineSemanticWindowResult.Deferred(failure.reason)
        }
        // Storage/stale-reference failures and CancellationException deliberately propagate.
    }

    private class Invalid(val reason: TimelineSemanticWindowResult.Reason) : Exception()
    private fun invalid(): Nothing = throw Invalid(TimelineSemanticWindowResult.Reason.Malformed)
    private fun structural(): Nothing = throw Invalid(TimelineSemanticWindowResult.Reason.StructuralLimit)

    private class Parser(
        val reference: TimelineBodyReference,
        val field: TimelineSemanticField,
        val start: Long,
        val budget: TimelineSemanticBudget,
        val read: suspend (Long, Int) -> TimelineBodyChunk,
    ) {
        var chunk = byteArrayOf()
        var index = 0
        var consumed = 0L
        var reads = 0
        var found = false
        var seen = false
        var messageType: String? = null
        var scalars = 0L
        var outputBytes = 0
        var emitted = 0L
        var full = false
        val output = StringBuilder()

        suspend fun peek(): Int {
            if (index == chunk.size) {
                if (consumed == reference.pointer.encodedBytes) return -1
                currentCoroutineContext().ensureActive()
                val size = minOf(budget.chunkBytes.toLong(), reference.pointer.encodedBytes - consumed).toInt()
                if (reads == budget.maxReads) throw Invalid(TimelineSemanticWindowResult.Reason.ReadBudget)
                val next = read(consumed, size)
                check(next.reference == reference && next.offset == consumed) { "Stale semantic chunk" }
                check(next.bytes.isNotEmpty() && next.bytes.size <= size) { "Invalid semantic chunk size" }
                chunk = next.bytes
                index = 0
                reads++
            }
            return chunk[index].toInt() and 255
        }
        suspend fun take(): Int = peek().also { if (it < 0) invalid() else { index++; consumed++ } }
        suspend fun expect(value: Int) { if (take() != value) invalid() }
        suspend fun space() { while (isSpace(peek())) take() }

        suspend fun parse(): TimelineSemanticWindowResult {
            space()
            if (peek() != 123) invalid()
            value(0)
            space()
            if (peek() != -1) invalid()
            // The ledger stores the enum name (ASSISTANT, TOOL_CALL); the wire uses the lower-case
            // form. Lower-casing maps every enum name onto the wire name already listed, so a
            // stored body is readable without widening what counts as supported.
            if (messageType?.lowercase() !in SUPPORTED_TYPES) {
                return TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.UnsupportedType)
            }
            if (!found) return TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField)
            return TimelineSemanticWindowResult.Text(output.toString(),
                if (full) start + emitted else null, consumed, outputBytes, reads)
        }

        suspend fun value(depth: Int, selected: Boolean = false) {
            if (depth > 32) structural()
            space()
            if (selected && peek() != 34 && peek() != 110) invalid()
            when (peek()) {
                34 -> string(if (selected) 2 else 0)
                123 -> {
                    take(); space()
                    if (peek() == 125) { take(); return }
                    while (true) {
                        space()
                        val key = string(1)
                        space(); expect(58)
                        val match = depth == 0 && key == field.wireName
                        if (match) { if (seen) invalid(); seen = true; found = true }
                        if (depth == 0 && key == "messageType") {
                            if (messageType != null) invalid()
                            space()
                            messageType = string(1)
                        } else value(depth + 1, match)
                        space()
                        if (peek() == 125) { take(); break }
                        expect(44)
                    }
                }
                91 -> {
                    take(); space()
                    if (peek() == 93) { take(); return }
                    while (true) {
                        value(depth + 1); space()
                        if (peek() == 93) { take(); break }
                        expect(44)
                    }
                }
                else -> {
                    val token = StringBuilder()
                    while (peek() != -1 && peek() !in listOf(32, 9, 10, 13, 44, 93, 125)) {
                        if (token.length == 128) structural()
                        token.append(take().toChar())
                    }
                    val text = token.toString()
                    if (selected && text != "null") invalid()
                    if (text !in listOf("true", "false", "null") && !NUMBER.matches(text)) invalid()
                    if (selected) found = false
                }
            }
        }

        suspend fun hex(): Int {
            var result = 0
            repeat(4) {
                val digit = take().toChar().digitToIntOrNull(16) ?: invalid()
                result = result * 16 + digit
            }
            return result
        }

        suspend fun scalar(): Int {
            val first = take()
            if (first == 92) {
                return when (take()) {
                    34 -> 34; 92 -> 92; 47 -> 47; 98 -> 8; 102 -> 12; 110 -> 10; 114 -> 13; 116 -> 9
                    117 -> {
                        val high = hex()
                        when (high) {
                            in 0xD800..0xDBFF -> {
                                expect(92); expect(117)
                                val low = hex()
                                if (low !in 0xDC00..0xDFFF) invalid()
                                0x10000 + (high - 0xD800) * 1024 + low - 0xDC00
                            }
                            in 0xDC00..0xDFFF -> invalid()
                            else -> high
                        }
                    }
                    else -> invalid()
                }
            }
            if (first < 32) invalid()
            if (first < 128) return first
            val count = when (first) { in 0xC2..0xDF -> 1; in 0xE0..0xEF -> 2; in 0xF0..0xF4 -> 3; else -> invalid() }
            var result = first and (0x7F shr (count + 1))
            repeat(count) {
                val next = take()
                if (next !in 0x80..0xBF) invalid()
                result = (result shl 6) or (next and 63)
            }
            if (result < when (count) { 1 -> 128; 2 -> 2048; else -> 65536 } ||
                result > 0x10FFFF || result in 0xD800..0xDFFF) invalid()
            return result
        }

        suspend fun string(mode: Int): String? {
            expect(34)
            val key = if (mode == 1) StringBuilder() else null
            while (peek() != 34) {
                val code = scalar()
                if (mode == 1) {
                    if (key!!.length + (if (code < 65536) 1 else 2) > 128) structural()
                    append(key, code)
                }
                if (mode == 2) {
                    if (scalars++ >= start && !full) {
                        val bytes = when { code < 128 -> 1; code < 2048 -> 2; code < 65536 -> 3; else -> 4 }
                        if (outputBytes + bytes > budget.maxOutputBytes) full = true
                        else { append(output, code); outputBytes += bytes; emitted++ }
                    }
                }
            }
            take()
            return key?.toString()
        }
        fun append(target: StringBuilder, code: Int) {
            if (code < 65536) target.append(code.toChar())
            else { target.append((0xD800 + ((code - 65536) shr 10)).toChar()); target.append((0xDC00 + ((code - 65536) and 1023)).toChar()) }
        }
    }
    private fun isSpace(byte: Int) = byte == 32 || byte == 9 || byte == 10 || byte == 13
    private val SUPPORTED_TYPES = setOf("assistant", "assistant_message", "user", "user_message",
        "reasoning", "reasoning_message", "tool_return", "tool_return_message", "tool_call", "tool_call_message")
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
