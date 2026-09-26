package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which App Server models the wrapper exposes to clients' model pickers
 * (letta-mobile-w4q4p). The App Server has no notion of hiding a model, so
 * this is wrapper-owned state keyed by model handle.
 *
 * Default: every handle is exposed. Only explicit decisions are stored, so a
 * model that appears later (a newly connected provider) shows up until someone
 * hides it.
 */
interface ModelExposureStore {
    /** Explicit per-handle decisions (`true` = exposed). Absent handles are exposed. */
    fun decisions(): Map<String, Boolean>

    /** Applies [changes] and returns the resulting decisions. */
    fun apply(changes: Map<String, Boolean>): Map<String, Boolean>

    fun isExposed(handle: String): Boolean = decisions()[handle] ?: true
}

/** Persisted file shape: `{"version":1,"models":{"<handle>":false}}`. */
@Serializable
internal data class ModelExposureDocument(
    val version: Int = ModelExposureDocuments.VERSION,
    val models: Map<String, Boolean> = emptyMap(),
)

internal object ModelExposureDocuments {
    const val VERSION = 1

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Merges [changes] into [current]. An exposed decision is dropped rather
     * than stored because exposed is the default: the file then only lists
     * hidden handles and stays small.
     */
    fun merge(current: Map<String, Boolean>, changes: Map<String, Boolean>): Map<String, Boolean> {
        val next = current.toMutableMap()
        changes.forEach { (handle, exposed) ->
            if (exposed) next.remove(handle) else next[handle] = false
        }
        return next.toMap()
    }
}

/** Non-persistent store for routers built without an exposure file (tests, stub CLI). */
class InMemoryModelExposureStore(initial: Map<String, Boolean> = emptyMap()) : ModelExposureStore {
    private val lock = ReentrantLock()
    private var current: Map<String, Boolean> = ModelExposureDocuments.merge(emptyMap(), initial)

    override fun decisions(): Map<String, Boolean> = lock.withLock { current }

    override fun apply(changes: Map<String, Boolean>): Map<String, Boolean> = lock.withLock {
        current = ModelExposureDocuments.merge(current, changes)
        current
    }
}

/**
 * JSON-file store written atomically (temp file + atomic move). Only the
 * wrapper writes it, so it is read once and then served from memory. An
 * unreadable file is treated as "no decisions" so a corrupt file can never
 * hide the whole catalog.
 */
class FileModelExposureStore(private val file: File) : ModelExposureStore {
    private val lock = ReentrantLock()

    @Volatile private var cached: Map<String, Boolean>? = null

    override fun decisions(): Map<String, Boolean> = cached ?: lock.withLock { load() }

    override fun apply(changes: Map<String, Boolean>): Map<String, Boolean> = lock.withLock {
        val next = ModelExposureDocuments.merge(load(), changes)
        write(next)
        cached = next
        next
    }

    private fun load(): Map<String, Boolean> =
        cached ?: read().also { cached = it }

    private fun read(): Map<String, Boolean> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            ModelExposureDocuments.json
                .decodeFromString(ModelExposureDocument.serializer(), file.readText())
                .models
        }.getOrDefault(emptyMap())
    }

    private fun write(decisions: Map<String, Boolean>) {
        val parent = checkNotNull(file.absoluteFile.parentFile) { "exposure file has no parent: $file" }
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        val document = ModelExposureDocument(models = decisions.toSortedMap())
        temp.writeText(ModelExposureDocuments.json.encodeToString(ModelExposureDocument.serializer(), document))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val FILE_NAME = "model-exposure.json"

        /**
         * [override] when set; otherwise beside `host-canvases.json`, i.e. the
         * sibling of the canvas op-log dir ([canvasOpsDir], default
         * `~/.letta/canvas-relay/topics`) — both are host-owned JSON state.
         */
        fun resolvePath(override: String?, canvasOpsDir: String?): String {
            if (!override.isNullOrBlank()) return override
            val opsDir = canvasOpsDir?.let(::File) ?: File(File(System.getProperty("user.home") ?: "."), ".letta/canvas-relay/topics")
            return File(opsDir.absoluteFile.parentFile, FILE_NAME).path
        }
    }
}
