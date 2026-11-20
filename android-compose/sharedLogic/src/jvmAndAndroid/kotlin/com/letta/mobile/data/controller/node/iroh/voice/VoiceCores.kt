package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.voice.core.VoiceCore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where the host finds an agent's memory block file by label; null when it has no such store. */
internal fun interface AgentBlockFiles {
    /** The file that holds [agentId]'s [label] block (it need not exist); null when it cannot be named. */
    fun fileOf(agentId: AgentId, label: String): File?
}

/**
 * The voice core of each agent, read on the host from the agent's own `voice` memory block, never
 * from a request, so neither a client nor a spoken reply can set it.
 *
 * Each agent's core is cached with the block file's modification time and size; a lookup checks those
 * and reads the block again when either changed (an edit by the agent or the person), so a new
 * description is used from the next segment on. An edit that keeps both the same is caught too: an
 * entry is trusted for at most [maxAge] milliseconds of [clock] before the block is read again. A read
 * that fails is not cached, so the next lookup tries again. An agent with no block, an empty one, or
 * no block store at all has no core: the speech server's stored voice speaks.
 */
internal class VoiceCores(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAge: Long = MAX_AGE_MS,
    private val reader: (File) -> String = File::readText,
    private val files: AgentBlockFiles?,
) {
    private val cache = ConcurrentHashMap<AgentId, Cached>()

    /** How many times a block was read rather than served from the cache; for tests. */
    @Volatile
    var reads: Int = 0
        private set

    /** [agentId]'s voice core, or null when it has none (or no agent is named). */
    fun coreOf(agentId: AgentId?): VoiceCore? {
        val agent = agentId ?: return null
        val file = runCatching { files?.fileOf(agent, VoiceCore.BLOCK_LABEL) }.getOrNull()
        val stamp = file?.let(Stamp::of)
        if (file == null || stamp == null) {
            cache.remove(agent)
            return null
        }
        val now = clock()
        val hit = cache[agent]?.takeIf { it.stamp == stamp && now - it.readAt in 0 until maxAge }
        return if (hit != null) hit.core else read(agent, file, stamp, now)
    }

    private fun read(agent: AgentId, file: File, stamp: Stamp, now: Long): VoiceCore? {
        reads++
        val text = runCatching { reader(file) }.getOrElse {
            cache.remove(agent)
            return null
        }
        val core = VoiceCore.fromBlock(text)
        cache[agent] = Cached(stamp, core, now)
        return core
    }

    /** What identifies one version of a block file. */
    private data class Stamp(val modified: Long, val size: Long) {
        companion object {
            fun of(file: File): Stamp? = if (file.isFile) Stamp(file.lastModified(), file.length()) else null
        }
    }

    private class Cached(val stamp: Stamp, val core: VoiceCore?, val readAt: Long)

    private companion object {
        /** How long an unchanged-looking entry is trusted before its block is read again. */
        const val MAX_AGE_MS = 30_000L
    }
}
