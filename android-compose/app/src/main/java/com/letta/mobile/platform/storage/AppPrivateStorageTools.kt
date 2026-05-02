package com.letta.mobile.platform.storage

import android.content.Context
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface AppPrivateStorageRootProvider {
    fun rootDirectory(root: AppPrivateStorageRoot): File
}

@Singleton
class AndroidAppPrivateStorageRootProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppPrivateStorageRootProvider {
    override fun rootDirectory(root: AppPrivateStorageRoot): File = when (root) {
        AppPrivateStorageRoot.Files -> context.filesDir
        AppPrivateStorageRoot.Cache -> context.cacheDir
        AppPrivateStorageRoot.NoBackup -> context.noBackupFilesDir
    }
}

@Singleton
class AppPrivateStorageTools @Inject constructor(
    private val rootProvider: AppPrivateStorageRootProvider,
    private val capabilityRegistry: SystemAccessCapabilityRegistry,
) {
    fun list(
        root: AppPrivateStorageRoot,
        path: String = ".",
        limit: Int = StorageAccessLimits.DEFAULT_MAX_DIRECTORY_ENTRIES,
    ): StorageToolResult<StorageListResponse> = withToolAccess(AndroidStorageToolIds.AppPrivateRead) {
        val safeLimit = limit.coerceIn(1, StorageAccessLimits.DEFAULT_MAX_DIRECTORY_ENTRIES)
        val directory = resolveContained(root, path).getOrElse { exception ->
            return@withToolAccess invalidPathFailure(exception)
        }
        if (!directory.exists()) {
            return@withToolAccess storageFailure(StorageToolErrorCode.NotFound, "Path does not exist: $path")
        }
        if (!directory.isDirectory) {
            return@withToolAccess storageFailure(StorageToolErrorCode.NotDirectory, "Path is not a directory: $path")
        }

        val files = directory.listFiles().orEmpty().sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() },
        )
        val entries = files.take(safeLimit).map { file -> file.toEntry(root) }
        StorageToolResult.Success(
            StorageListResponse(
                root = root,
                path = normalizeDisplayPath(path),
                entries = entries,
                truncated = files.size > safeLimit,
            ),
        )
    }

    fun read(
        root: AppPrivateStorageRoot,
        path: String,
        maxBytes: Long = StorageAccessLimits.DEFAULT_MAX_READ_BYTES,
    ): StorageToolResult<StorageReadResponse> = withToolAccess(AndroidStorageToolIds.AppPrivateRead) {
        val safeMaxBytes = maxBytes.coerceIn(1, StorageAccessLimits.DEFAULT_MAX_READ_BYTES)
        val file = resolveContained(root, path).getOrElse { exception ->
            return@withToolAccess invalidPathFailure(exception)
        }
        if (!file.exists()) {
            return@withToolAccess storageFailure(StorageToolErrorCode.NotFound, "Path does not exist: $path")
        }
        if (file.isDirectory) {
            return@withToolAccess storageFailure(StorageToolErrorCode.IsDirectory, "Path is a directory: $path")
        }

        try {
            val (content, truncated) = file.inputStream().use { input ->
                input.readCapped(safeMaxBytes.toInt())
            }
            StorageToolResult.Success(
                StorageReadResponse(
                    root = root,
                    path = normalizeDisplayPath(path),
                    content = content,
                    bytesRead = content.size,
                    truncated = truncated,
                ),
            )
        } catch (exception: IOException) {
            storageFailure(StorageToolErrorCode.IoError, exception.message ?: "Unable to read file")
        }
    }

    fun write(
        root: AppPrivateStorageRoot,
        path: String,
        content: ByteArray,
        overwrite: Boolean = false,
    ): StorageToolResult<StorageWriteResponse> = withToolAccess(AndroidStorageToolIds.AppPrivateWrite) {
        if (content.size > StorageAccessLimits.DEFAULT_MAX_WRITE_BYTES) {
            return@withToolAccess storageFailure(
                StorageToolErrorCode.TooLarge,
                "Write payload exceeds ${StorageAccessLimits.DEFAULT_MAX_WRITE_BYTES} bytes",
            )
        }

        val file = resolveContained(root, path).getOrElse { exception ->
            return@withToolAccess invalidPathFailure(exception)
        }
        if (file.exists() && file.isDirectory) {
            return@withToolAccess storageFailure(StorageToolErrorCode.IsDirectory, "Path is a directory: $path")
        }
        if (file.exists() && !overwrite) {
            return@withToolAccess storageFailure(StorageToolErrorCode.AlreadyExists, "Path already exists: $path")
        }

        try {
            val created = !file.exists()
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            StorageToolResult.Success(
                StorageWriteResponse(
                    root = root,
                    path = normalizeDisplayPath(path),
                    bytesWritten = content.size,
                    created = created,
                ),
            )
        } catch (exception: IOException) {
            storageFailure(StorageToolErrorCode.IoError, exception.message ?: "Unable to write file")
        }
    }

    private inline fun <T> withToolAccess(
        toolId: String,
        block: () -> StorageToolResult<T>,
    ): StorageToolResult<T> {
        val check = capabilityRegistry.checkToolAccess(toolId)
        if (!check.allowed) {
            return storageFailure(StorageToolErrorCode.ToolDenied, check.reason)
        }
        return block()
    }

    private fun resolveContained(
        root: AppPrivateStorageRoot,
        path: String,
    ): Result<File> = runCatching {
        if (path.isBlank()) {
            throw InvalidStoragePathException("Path cannot be blank")
        }
        val requested = File(path)
        if (requested.isAbsolute) {
            throw InvalidStoragePathException("Absolute paths are not allowed")
        }

        val canonicalRoot = rootProvider.rootDirectory(root).canonicalFile
        val candidate = File(canonicalRoot, path).canonicalFile
        if (candidate != canonicalRoot && !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
            throw InvalidStoragePathException("Path escapes the approved app-private root")
        }
        candidate
    }

    private fun File.toEntry(root: AppPrivateStorageRoot): StorageFileEntry {
        val rootFile = rootProvider.rootDirectory(root).canonicalFile
        val relativePath = relativeTo(rootFile).invariantSeparatorsPath.ifBlank { "." }
        return StorageFileEntry(
            name = name,
            path = relativePath,
            isDirectory = isDirectory,
            sizeBytes = if (isFile) length() else null,
            lastModifiedMillis = lastModified(),
        )
    }
}

private fun invalidPathFailure(exception: Throwable): StorageToolResult.Failure =
    storageFailure(StorageToolErrorCode.InvalidPath, exception.message ?: "Invalid path")

private fun normalizeDisplayPath(path: String): String = path
    .trim()
    .ifBlank { "." }
    .removePrefix("./")
    .ifBlank { "." }

private class InvalidStoragePathException(message: String) : Exception(message)
