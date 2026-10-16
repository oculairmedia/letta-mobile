package com.letta.mobile.desktop.runtime

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One process as the OS reports it. [startToken] is the OS start time and [commandLine] what the
 * OS says was launched; both are compared verbatim, so they must come from the same source at
 * spawn and at reap.
 */
internal data class DesktopProcessIdentity(
    val pid: Long,
    val startToken: String,
    val commandLine: String,
)

/** A live OS process that can be identified and killed (with its descendants). */
internal interface DesktopLiveProcess {
    val identity: DesktopProcessIdentity?

    /** Kills the process tree; returns false when it could not be signalled. */
    fun killTree(): Boolean
}

/** Looks processes up by pid; faked in tests. */
internal fun interface DesktopProcessTable {
    fun find(pid: Long): DesktopLiveProcess?
}

/** What a launch-time reap did, for the runtime log. */
internal data class DesktopReapReport(
    val killed: List<Long> = emptyList(),
    val skipped: List<String> = emptyList(),
    val corrupt: Boolean = false,
)

@Serializable
private data class SpawnRegistryFile(
    val version: Int = 1,
    val entries: List<SpawnRegistryEntry> = emptyList(),
)

@Serializable
private data class SpawnRegistryEntry(
    val pid: Long,
    @SerialName("start_token") val startToken: String,
    @SerialName("command_line") val commandLine: String,
    /** The desktop process that spawned it; an entry whose owner still runs is not an orphan. */
    @SerialName("owner_pid") val ownerPid: Long,
    @SerialName("owner_start_token") val ownerStartToken: String,
)

/**
 * letta-mobile-zxk2n.1 (G04): a persisted record of the runtime children this desktop spawned, so
 * a crashed or killed desktop does not leave a node child holding the listener port forever.
 *
 * At launch [reapOrphans] kills a recorded child only when its pid, OS start token and command
 * line all still match, and its owning desktop process is gone: a reused pid has a different
 * start token and is left alone. An unreadable registry reaps nothing (fail closed) and is
 * replaced on the next spawn. Every method is safe to call from any thread.
 */
internal class DesktopListenerSpawnRegistry(
    private val file: File,
    private val processTable: DesktopProcessTable,
    private val self: () -> DesktopProcessIdentity?,
    private val logLine: (String) -> Unit = {},
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Records [childPid] as spawned by this desktop. */
    fun onSpawned(childPid: Long) {
        val child = processTable.find(childPid)?.identity ?: return
        val owner = self() ?: return
        synchronized(lock) {
            val kept = readOrEmpty().entries.filterNot { it.pid == child.pid }
            write(SpawnRegistryFile(entries = kept + child.toEntry(owner)))
        }
    }

    /** Forgets [childPid] after it was stopped or exited. */
    fun onEnded(childPid: Long) {
        synchronized(lock) {
            val current = readOrEmpty()
            val kept = current.entries.filterNot { it.pid == childPid }
            if (kept.size != current.entries.size) write(current.copy(entries = kept))
        }
    }

    /** Kills recorded children whose identity still matches and whose owner is gone. */
    fun reapOrphans(): DesktopReapReport = synchronized(lock) {
        val registry = read() ?: return@synchronized DesktopReapReport(corrupt = true).also {
            logLine("[spawn-registry] unreadable registry; nothing reaped")
        }
        val killed = mutableListOf<Long>()
        val skipped = mutableListOf<String>()
        val stillOwned = mutableListOf<SpawnRegistryEntry>()
        registry.entries.forEach { entry ->
            when (val verdict = verdict(entry)) {
                null -> if (processTable.find(entry.pid)?.killTree() == true) killed += entry.pid
                OWNER_RUNNING -> stillOwned += entry
                else -> skipped += "${entry.pid}: $verdict"
            }
        }
        // Dead, reused and reaped entries are dropped; another running desktop keeps its own.
        write(SpawnRegistryFile(entries = stillOwned))
        killed.forEach { logLine("[spawn-registry] reaped orphaned runtime pid=$it") }
        skipped.forEach { logLine("[spawn-registry] left alone $it") }
        DesktopReapReport(killed = killed, skipped = skipped)
    }

    /** Null when [entry] is a verified orphan of ours; otherwise why it is left alone. */
    private fun verdict(entry: SpawnRegistryEntry): String? {
        val owner = processTable.find(entry.ownerPid)?.identity
        val live = processTable.find(entry.pid)?.identity
        return when {
            owner != null && owner.startToken == entry.ownerStartToken -> OWNER_RUNNING
            live == null -> "not running"
            live.startToken.isBlank() || live.startToken != entry.startToken -> "pid reused (start time differs)"
            live.commandLine.isBlank() || live.commandLine != entry.commandLine -> "command line differs"
            else -> null
        }
    }

    private fun DesktopProcessIdentity.toEntry(owner: DesktopProcessIdentity) = SpawnRegistryEntry(
        pid = pid,
        startToken = startToken,
        commandLine = commandLine,
        ownerPid = owner.pid,
        ownerStartToken = owner.startToken,
    )

    /** The registry, an empty one when there is no file, or null when the file is unreadable. */
    private fun read(): SpawnRegistryFile? {
        if (!file.isFile) return SpawnRegistryFile()
        return runCatching { json.decodeFromString(SpawnRegistryFile.serializer(), file.readText()) }
            .getOrNull()
            ?.takeIf { it.version == 1 }
    }

    private fun readOrEmpty(): SpawnRegistryFile = read() ?: SpawnRegistryFile()

    private fun write(registry: SpawnRegistryFile) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(SpawnRegistryFile.serializer(), registry))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }.onFailure { logLine("[spawn-registry] could not write ${file.name}: ${it.message}") }
    }

    private companion object {
        const val OWNER_RUNNING = "owner still running"
    }
}

/** [DesktopProcessTable] over the JDK's [ProcessHandle]. */
internal object JvmDesktopProcessTable : DesktopProcessTable {
    override fun find(pid: Long): DesktopLiveProcess? =
        ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }?.let(::JvmDesktopLiveProcess)

    fun self(): DesktopProcessIdentity? = JvmDesktopLiveProcess(ProcessHandle.current()).identity
}

private class JvmDesktopLiveProcess(private val handle: ProcessHandle) : DesktopLiveProcess {
    override val identity: DesktopProcessIdentity?
        get() {
            val info = handle.info()
            val start = info.startInstant().orElse(null)?.toString() ?: return null
            // Windows exposes only the executable, not the arguments; both spawn and reap read
            // the same field, so the comparison stays like for like.
            val command = info.commandLine().orElse(null) ?: info.command().orElse(null) ?: return null
            return DesktopProcessIdentity(handle.pid(), start, command)
        }

    override fun killTree(): Boolean {
        handle.descendants().forEach { it.destroyForcibly() }
        return handle.destroyForcibly()
    }
}
