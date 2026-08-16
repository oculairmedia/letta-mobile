@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.fs

import com.letta.mobile.data.timeline.timelineLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * Kotlin/Wasm external bindings for W3C File System Access API.
 */
external interface FileSystemHandle : JsAny {
    val kind: String // "file" or "directory"
    val name: String
}

external interface FileSystemFileHandle : FileSystemHandle {
    fun getFile(): Promise<JsFile>
    fun createWritable(): Promise<FileSystemWritableFileStream>
}

external interface FileSystemDirectoryHandle : FileSystemHandle {
    fun getFileHandle(name: String, options: JsAny = definedExternally): Promise<FileSystemFileHandle>
    fun getDirectoryHandle(name: String, options: JsAny = definedExternally): Promise<FileSystemDirectoryHandle>
    fun removeEntry(name: String, options: JsAny = definedExternally): Promise<JsAny?>
    fun values(): JsAsyncIterable
}

external interface FileSystemWritableFileStream : JsAny {
    fun write(data: String): Promise<JsAny?>
    fun close(): Promise<JsAny?>
}

external interface JsFile : JsAny {
    val name: String
    val size: Double
    fun text(): Promise<JsString>
}

external interface JsAsyncIterable : JsAny

external interface WindowWithFileSystem : JsAny {
    fun showDirectoryPicker(): Promise<FileSystemDirectoryHandle>
}

/**
 * Controller managing browser local workspace directory via File System Access API.
 */
class WebWorkspaceController {
    private val logger = timelineLogger("WebWorkspace")
    var rootHandle: FileSystemDirectoryHandle? = null
        private set

    val isWorkspaceOpen: Boolean
        get() = rootHandle != null

    val workspaceName: String
        get() = rootHandle?.name ?: "No Workspace"

    suspend fun openWorkspaceDirectory(): Boolean {
        return try {
            val handle = promptDirectoryPicker().await()
            rootHandle = handle
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.warn("Directory picker cancelled or unsupported", t)
            false
        }
    }

    suspend fun readFile(fileName: String): String? {
        val handle = rootHandle ?: return null
        return try {
            val fileHandle = handle.getFileHandle(fileName).await()
            val file = fileHandle.getFile().await()
            file.text().await().toString()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.error("Failed to read workspace file", t)
            null
        }
    }

    suspend fun writeFile(fileName: String, content: String): Boolean {
        val handle = rootHandle ?: return false
        return try {
            val fileHandle = handle.getFileHandle(fileName, createFileOptions()).await()
            val writable = fileHandle.createWritable().await()
            writable.write(content).await()
            writable.close().await()
            true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logger.error("Failed to write workspace file", t)
            false
        }
    }
}

private fun promptDirectoryPicker(): Promise<FileSystemDirectoryHandle> =
    js("window.showDirectoryPicker()")

private fun createFileOptions(): JsAny = js("({ create: true })")
