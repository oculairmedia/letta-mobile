package com.letta.mobile.data.canvas

import com.letta.mobile.data.model.CanvasId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * [FileCanvasOpLog]'s journal ([CanvasOpLog.unsettled],): the settled op ids
 * of each canvas in a sidecar file, one per line. A log that has no sidecar was written before the
 * journal existed; all of its ops are taken as settled the first time it is read, since the
 * document they were written beside is the only record of which of them it holds.
 *
 * Not thread-safe: the op log calls it under its own lock.
 */
internal class FileCanvasOpJournal(
    private val sidecarOf: (CanvasId) -> Path,
    private val loggedIds: (CanvasId) -> Set<String>,
    private val decode: (String) -> CanvasOp,
) {
    private val settledByCanvas = mutableMapOf<CanvasId, MutableSet<String>>()

    /** Settled op ids of [canvasId], loading (or seeding a pre-journal log's) sidecar once. */
    fun settled(canvasId: CanvasId): MutableSet<String> {
        settledByCanvas[canvasId]?.let { return it }
        val sidecar = sidecarOf(canvasId)
        val settled = if (Files.isRegularFile(sidecar)) readIds(sidecar) else seed(sidecar, loggedIds(canvasId))
        settledByCanvas[canvasId] = settled
        return settled
    }

    /** Ops in [logFile] not yet settled, in the order they were appended; corrupted lines are skipped. */
    fun unsettled(canvasId: CanvasId, logFile: Path): List<CanvasOp> {
        val settled = settled(canvasId)
        if (!Files.isRegularFile(logFile)) return emptyList()
        val lines = Files.readAllLines(logFile).map { it.trim() }.filter { it.isNotEmpty() }
        return lines.mapNotNull(::decodeOrNull).filterNot { it.opId in settled }
    }

    fun settle(canvasId: CanvasId, opIds: Collection<String>) {
        val settled = settled(canvasId)
        val fresh = opIds.filter { settled.add(it) }
        if (fresh.isEmpty()) return
        write(sidecarOf(canvasId), fresh, StandardOpenOption.APPEND)
    }

    private fun readIds(sidecar: Path): MutableSet<String> {
        return Files.readAllLines(sidecar).map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
    }

    private fun seed(sidecar: Path, legacy: Set<String>): MutableSet<String> {
        write(sidecar, legacy, StandardOpenOption.TRUNCATE_EXISTING)
        return legacy.toMutableSet()
    }

    private fun write(file: Path, ids: Collection<String>, mode: StandardOpenOption) {
        Files.writeString(file, ids.joinToString("") { it + "\n" }, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode)
    }

    private fun decodeOrNull(line: String): CanvasOp? {
        return try {
            decode(line)
        } catch (_: IllegalArgumentException) {
            null // a corrupted line (SerializationException is one)
        }
    }
}
