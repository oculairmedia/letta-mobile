package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpLog
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Desktop file-backed append-only JSONL implementation of [CanvasOpLog].
 *
 * Persists ops for each canvas into `{rootDirectory}/ops/{canvasId_sha256}.jsonl`
 * while keeping an in-memory index of seen op IDs for O(1) deduplication.
 */
class DesktopCanvasOpLog(
    private val rootDirectory: Path = defaultOpsDirectory(),
) : CanvasOpLog {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val opIdsByCanvas = mutableMapOf<CanvasId, MutableSet<String>>()
    private val flowsByCanvas = ConcurrentHashMap<CanvasId, MutableSharedFlow<CanvasOp>>()

    init {
        Files.createDirectories(rootDirectory)
    }

    override suspend fun append(canvasId: CanvasId, op: CanvasOp) {
        withContext(Dispatchers.IO) {
            writeOpToFile(canvasId, op)
        }
    }

    private suspend fun writeOpToFile(canvasId: CanvasId, op: CanvasOp) {
        mutex.withLock {
            val opIds = getOrLoadOpIds(canvasId)
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

    override suspend fun getOps(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> {
        return withContext(Dispatchers.IO) {
            readOpsFromFile(canvasId, sinceLamport)
        }
    }

    private suspend fun readOpsFromFile(canvasId: CanvasId, sinceLamport: Long): List<CanvasOp> {
        return mutex.withLock {
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
                    } catch (e: CancellationException) {
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

    override suspend fun has(canvasId: CanvasId, opId: String): Boolean {
        return withContext(Dispatchers.IO) {
            checkOpExists(canvasId, opId)
        }
    }

    private suspend fun checkOpExists(canvasId: CanvasId, opId: String): Boolean {
        return mutex.withLock {
            val opIds = getOrLoadOpIds(canvasId)
            opIds.contains(opId)
        }
    }

    override fun observe(canvasId: CanvasId): Flow<CanvasOp> {
        val flow = flowsByCanvas.computeIfAbsent(canvasId) {
            MutableSharedFlow<CanvasOp>(replay = 16, extraBufferCapacity = 64)
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
