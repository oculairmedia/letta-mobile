package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * The newest [count] local messages, newest first, projected for their ids, types and content
 * (attachments included) only: read from the end of the transcript, so the cost follows [count]
 * rather than the transcript's length. Real times, otids and run ids are not looked up, so those
 * fields are not what [LocalBackendMessageReader.listMessagesProjected] would serve. Lines that are
 * not messages count against a window of twice [count] lines, so fewer can come back. Null on any
 * error.
 */
internal fun LocalBackendMessageReader.newestMessageContents(conversationId: ConversationId, count: Int): JsonArray? = runCatching {
    val (internalConvId, agentId) = resolveConversation(conversationId.value, null) ?: return@runCatching JsonArray(emptyList())
    val dir = File(File(support.baseDir, "conversations"), support.b64UrlEncode(support.conversationKey(internalConvId, agentId)))
    val newest = File(dir, "messages.jsonl").tailLines(maxLines = count * 2, maxBytes = TAIL_MAX_BYTES)
        .mapNotNull { line -> runCatching { support.json.parseToJsonElement(line) }.getOrNull() as? JsonObject }
        .mapNotNull { normalizeMessage(it)?.takeIf(::isLocalMessage) }
        .takeLast(count)
    val sidecars = MessageSidecars(
        realTimes = emptyMap(),
        otid = emptyMap(),
        attachments = support.readAttachmentMap(File(dir, "_attachments.json")),
        runIds = emptyMap(),
    )
    buildJsonArray {
        newest.asReversed().forEach { m ->
            projection.localMessageToConversationMessages(hydrateSmallImages(dir, m), sidecars).forEach { add(it) }
        }
    }
}.getOrNull()

/**
 * The last [maxLines] non-blank lines of this file, read backwards from its end so the cost follows
 * the lines wanted rather than the file's length. Reads at most [maxBytes] from the end; a line that
 * starts before that window is left out, so fewer lines can come back. Empty when the file is
 * missing or unreadable.
 */
internal fun File.tailLines(maxLines: Int, maxBytes: Long): List<String> = runCatching {
    RandomAccessFile(this, "r").use { file ->
        val length = file.length()
        var start = length
        var newlines = 0
        val chunks = ArrayDeque<ByteArray>()
        // One newline more than the lines wanted marks where the oldest wanted line begins.
        fun wantMore() = newlines <= maxLines && length - start < maxBytes
        while (start > 0 && wantMore()) {
            val size = minOf(TAIL_CHUNK_BYTES, start, maxBytes - (length - start)).toInt()
            start -= size
            val chunk = ByteArray(size)
            file.seek(start)
            file.readFully(chunk)
            newlines += chunk.count { it == NEWLINE }
            chunks.addFirst(chunk)
        }
        val bytes = ByteArrayOutputStream((length - start).toInt()).apply { chunks.forEach { write(it) } }
        val text = bytes.toString(Charsets.UTF_8.name())
        // The window's first line is cut unless it starts the file or follows a newline: drop it then.
        val cut = start > 0 && file.run { seek(start - 1); readByte() } != NEWLINE
        val whole = if (cut) text.substringAfter('\n', missingDelimiterValue = "") else text
        whole.split('\n').filter { it.isNotBlank() }.takeLast(maxLines)
    }
}.getOrDefault(emptyList())

private const val TAIL_CHUNK_BYTES = 64L * 1024

/** How far back from the end [newestMessageContents] reads. */
private const val TAIL_MAX_BYTES = 8L * 1024 * 1024
private const val NEWLINE = '\n'.code.toByte()
