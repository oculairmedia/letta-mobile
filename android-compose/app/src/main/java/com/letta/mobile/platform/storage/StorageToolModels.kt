package com.letta.mobile.platform.storage

/** Stable tool ids declared by the system-access capability registry. */
object AndroidStorageToolIds {
    const val AppPrivateRead = "storage.app_private.read"
    const val AppPrivateWrite = "storage.app_private.write"
    const val AppPrivateExport = "storage.app_private.export"
    const val AppPrivateCache = "storage.app_private.cache"
    const val SafRead = "storage.saf.read"
    const val SafWrite = "storage.saf.write"
    const val SafSearch = "storage.saf.search"
}

enum class AppPrivateStorageRoot {
    Files,
    Cache,
    NoBackup,
}

data class StorageAccessLimits(
    val maxReadBytes: Long = DEFAULT_MAX_READ_BYTES,
    val maxWriteBytes: Long = DEFAULT_MAX_WRITE_BYTES,
    val maxDirectoryEntries: Int = DEFAULT_MAX_DIRECTORY_ENTRIES,
) {
    init {
        require(maxReadBytes > 0) { "maxReadBytes must be positive" }
        require(maxWriteBytes > 0) { "maxWriteBytes must be positive" }
        require(maxDirectoryEntries > 0) { "maxDirectoryEntries must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_READ_BYTES: Long = 64 * 1024
        const val DEFAULT_MAX_WRITE_BYTES: Long = 512 * 1024
        const val DEFAULT_MAX_DIRECTORY_ENTRIES: Int = 200
    }
}

enum class StorageToolErrorCode {
    ToolDenied,
    UnknownTool,
    InvalidPath,
    MissingGrant,
    NotFound,
    AlreadyExists,
    IsDirectory,
    NotDirectory,
    TooLarge,
    IoError,
    Unsupported,
}

data class StorageToolError(
    val code: StorageToolErrorCode,
    val message: String,
)

sealed interface StorageToolResult<out T> {
    data class Success<T>(val value: T) : StorageToolResult<T>
    data class Failure(val error: StorageToolError) : StorageToolResult<Nothing>
}

data class StorageFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val lastModifiedMillis: Long,
)

data class StorageListResponse(
    val root: AppPrivateStorageRoot,
    val path: String,
    val entries: List<StorageFileEntry>,
    val truncated: Boolean,
)

data class StorageReadResponse(
    val root: AppPrivateStorageRoot? = null,
    val path: String,
    val content: ByteArray,
    val bytesRead: Int,
    val truncated: Boolean,
    val sourceUri: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StorageReadResponse
        return root == other.root &&
            path == other.path &&
            content.contentEquals(other.content) &&
            bytesRead == other.bytesRead &&
            truncated == other.truncated &&
            sourceUri == other.sourceUri
    }

    override fun hashCode(): Int {
        var result = root?.hashCode() ?: 0
        result = 31 * result + path.hashCode()
        result = 31 * result + content.contentHashCode()
        result = 31 * result + bytesRead
        result = 31 * result + truncated.hashCode()
        result = 31 * result + (sourceUri?.hashCode() ?: 0)
        return result
    }
}

data class StorageWriteResponse(
    val root: AppPrivateStorageRoot? = null,
    val path: String,
    val bytesWritten: Int,
    val created: Boolean,
    val destinationUri: String? = null,
)

data class SafStorageGrant(
    val uri: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val persistedAtMillis: Long,
)

fun storageFailure(
    code: StorageToolErrorCode,
    message: String,
): StorageToolResult.Failure = StorageToolResult.Failure(StorageToolError(code, message))
