package com.letta.mobile.data.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The app's durable canvas delivery record ([CanvasDeliveryStore]) in one JSON file, rewritten
 * atomically (temporary file, then rename) on every change, so a queued edit survives a crash or a
 * restart. Used by Android (app files) and desktop (`~/.letta/canvas`) alike.
 */
class FileCanvasDeliveryStore(private val file: File) : StateCanvasDeliveryStore() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): CanvasDeliveryState = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext CanvasDeliveryState()
        runCatching { json.decodeFromString(CanvasDeliveryState.serializer(), file.readText()) }
            .getOrElse { CanvasDeliveryState() }
    }

    override suspend fun save(state: CanvasDeliveryState) = withContext(Dispatchers.IO) {
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
