package com.letta.mobile.data.canvas

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The app's durable canvas delivery record ([CanvasDeliveryStore]) in one JSON file, rewritten
 * atomically (temporary file, then rename) on every change, so a queued edit survives a crash or a
 * restart. Used by Android (app files) and desktop (`~/.letta/canvas`) alike.
 *
 * Every read and write runs on [io], never on the caller's dispatcher: the relay client advances a
 * cursor for each op it applies, and that must not touch the disk on whatever thread delivered it.
 */
class FileCanvasDeliveryStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : StateCanvasDeliveryStore() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): CanvasDeliveryState = withContext(io) {
        if (!file.isFile) return@withContext CanvasDeliveryState()
        runCatching { json.decodeFromString(CanvasDeliveryState.serializer(), file.readText()) }
            .getOrElse { CanvasDeliveryState() }
    }

    override suspend fun save(state: CanvasDeliveryState) = withContext(io) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(CanvasDeliveryState.serializer(), state))
        if (!temp.renameTo(file)) {
            // Windows will not rename over an existing file.
            file.delete()
            check(temp.renameTo(file)) { "Could not write $file" }
        }
    }
}
