package com.letta.mobile.data.canvas

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * File-backed append-only JSONL implementation of [CanvasOpLog], for the desktop app and for
 * the Iroh host that relays canvases between apps.
 *
 * Persists ops for each canvas into `{rootDirectory}/ops/{canvasId_sha256}.jsonl`
 * while keeping an in-memory index of seen op IDs for O(1) deduplication.
 *
 * The journal ([CanvasOpLog.unsettled], letta-mobile-by4vm.22) is a sidecar of settled op ids,
 * `{canvasId_sha256}.settled`, one per line. A log written before the journal existed has no
 * sidecar; its ops are all taken as settled when it is first read, since the document they were
 * written beside is the only record of which of them it holds.
 */
class FileCanvasOpLog(
    private val rootDirectory: Path = defaultOpsDirectory(),
) : CanvasOpLog {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val opIdsByCanvas = mutableMapOf<CanvasId, MutableSet<String>>()
    private val settledByCanvas = mutableMapOf<CanvasId, MutableSet<String>>()
    private val flowsByCanvas = ConcurrentHashMap<CanvasId, MutableSharedFlow<CanvasOp>>()

    init {
        Files.createDirectories(rootDirectory)
    }

    override suspend fun append(canvasId: CanvasId, op: CanvasOp): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val opIds = getOrLoadOpIds(canvasId)
            getOrLoadSettled(canvasId)
            if (!opIds.add(op.opId)) {
                return@withLock
            }
            val file = opLogFile(canvasId)
            val line = json.encodeToString(CanvasOp.serializer(), op) + "\n"
            Files.writeString(
                file,
                line,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND,
            )
            flowsByCanvas[canvasId]?.emit(op)
        }
    }

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = opLogFile(canvasId)
            if (!Files.exists(file) || !Files.isRegularFile(file)) return@withLock emptyList()
            try {
                val lines = Files.readAllLines(file)
                val ops = mutableListOf<CanvasOp>()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    try {
                        val op = json.decodeFromString(CanvasOp.serializer(), trimmed)
                        if (op.lamport > sinceLamport) {
                            ops.add(op)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Skip corrupted line
                    }
                }
                ops.sortedBy { it.lamport }
            } catch (e: IOException) {
                emptyList()
            }
        }
    }

    override suspend fun has(canvasId: CanvasId, opId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val opIds = getOrLoadOpIds(canvasId)
            opIds.contains(opId)
        }
    }

    override suspend fun unsettled(canvasId: CanvasId): List<CanvasOp> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settled = getOrLoadSettled(canvasId)
            readOps(canvasId).filterNot { it.opId in settled }
        }
    }

    override suspend fun settle(canvasId: CanvasId, opIds: Collection<String>): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settled = getOrLoadSettled(canvasId)
            val fresh = opIds.filter { settled.add(it) }
            if (fresh.isNotEmpty()) appendLines(settledFile(canvasId), fresh)
        }
    }

    /** Settled op ids; a log without a sidecar predates the journal and is settled whole, once. */
    private fun getOrLoadSettled(canvasId: CanvasId): MutableSet<String> = settledByCanvas.getOrPut(canvasId) {
        val sidecar = settledFile(canvasId)
        if (Files.isRegularFile(sidecar)) {
            Files.readAllLines(sidecar).map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
        } else {
            val legacy = getOrLoadOpIds(canvasId).toMutableSet()
            Files.writeString(sidecar, legacy.joinToString("") { it + "\n" }, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            legacy
        }
    }

    /** Every op in [canvasId]'s file, in the order appended; corrupted lines are skipped. */
    private fun readOps(canvasId: CanvasId): List<CanvasOp> {
        val file = opLogFile(canvasId)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull { line -> line.trim().takeIf { it.isNotEmpty() }?.let(::decodeOrNull) }
    }

    private fun decodeOrNull(line: String): CanvasOp? = try {
        json.decodeFromString(CanvasOp.serializer(), line)
    } catch (_: IllegalArgumentException) {
        null // a corrupted line (SerializationException is one)
    }

    private fun appendLines(file: Path, lines: List<String>) {
        Files.writeString(file, lines.joinToString("") { it + "\n" }, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    }

    private fun settledFile(canvasId: CanvasId): Path = rootDirectory.resolve("${canvasId.value.sha256PathComponent()}.settled")

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> {
        val flow = flowsByCanvas.computeIfAbsent(canvasId) {
            MutableSharedFlow(replay = 16, extraBufferCapacity = 64)
        }
        return flow.asSharedFlow()
    }

    private fun getOrLoadOpIds(canvasId: CanvasId): MutableSet<String> {
        return opIdsByCanvas.getOrPut(canvasId) {
            val set = mutableSetOf<String>()
            val file = opLogFile(canvasId)
            if (Files.exists(file) && Files.isRegularFile(file)) {
                try {
                    val lines = Files.readAllLines(file)
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue
                        try {
                            val op = json.decodeFromString(CanvasOp.serializer(), trimmed)
                            set.add(op.opId)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: IOException) {
                }
            }
            set
        }
    }

    private fun opLogFile(canvasId: CanvasId): Path {
        val safeName = canvasId.value.sha256PathComponent()
        return rootDirectory.resolve("$safeName.jsonl")
    }

    companion object {
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        fun defaultOpsDirectory(): Path {
            val userHome = System.getProperty("user.home") ?: "."
            return Paths.get(userHome, ".letta", "canvas", "ops")
        }

        private fun String.sha256PathComponent(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray())
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX_DIGITS[value ushr 4])
                    append(HEX_DIGITS[value and 0x0f])
                }
            }
        }
    }
}
