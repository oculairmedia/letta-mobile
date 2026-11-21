package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The naming of the journal directory: which file holds a conversation, and reading a conversation's owner off the files. */
internal object FeedDirectory {
    private const val TAG = "FeedJournal"
    const val FILE_SUFFIX = ".jsonl"
    private const val FILE_SEPARATOR = "-"
    private const val HASH_CHARS = 24

    /** The file name of [key]'s journal: a hash of its conversation, then a hash of its agent. */
    fun fileName(key: JournalKey): String = hash(key.conversationId.value) + FILE_SEPARATOR + hash(key.agentId.value) + FILE_SUFFIX

    /** Every journal file in [dir]; empty (logged) when it cannot be listed. */
    fun journalFiles(dir: Path): List<Path> = try {
        Files.newDirectoryStream(dir, "*$FILE_SUFFIX").use { it.toList() }
    } catch (expected: IOException) {
        Telemetry.event(TAG, "directory.unreadable", "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
        emptyList()
    }

    /** The agent whose journal file for [conversation] exists in [dir], from the file's own header; null when there is none. */
    fun ownerOnDisk(dir: Path, conversation: ConversationId): AgentId? {
        val prefix = hash(conversation.value) + FILE_SEPARATOR
        return journalFiles(dir).firstOrNull { it.fileName.toString().startsWith(prefix) }
            ?.let { file -> runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull() }
            ?.let(FeedLogCodec::keyOf)?.takeIf { it.conversationId == conversation }?.agentId
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
}
