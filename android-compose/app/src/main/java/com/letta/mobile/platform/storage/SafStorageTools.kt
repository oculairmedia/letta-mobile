package com.letta.mobile.platform.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_SAF_SEARCH_QUERY_LENGTH = 80

interface SafStorageGrantStore {
    fun listGrants(): List<SafStorageGrant>
    fun hasReadGrant(uri: Uri): Boolean
    fun hasWriteGrant(uri: Uri): Boolean
    fun persistGrant(uri: Uri, flags: Int): StorageToolResult<SafStorageGrant>
}

@Singleton
class AndroidSafStorageGrantStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SafStorageGrantStore {
    private val contentResolver: ContentResolver
        get() = context.contentResolver

    override fun listGrants(): List<SafStorageGrant> = contentResolver.persistedUriPermissions.map { permission ->
        SafStorageGrant(
            uri = permission.uri.toString(),
            canRead = permission.isReadPermission,
            canWrite = permission.isWritePermission,
            persistedAtMillis = permission.persistedTime,
        )
    }.sortedBy { it.uri }

    override fun hasReadGrant(uri: Uri): Boolean = contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isReadPermission
    }

    override fun hasWriteGrant(uri: Uri): Boolean = contentResolver.persistedUriPermissions.any { permission ->
        permission.uri == uri && permission.isWritePermission
    }

    override fun persistGrant(uri: Uri, flags: Int): StorageToolResult<SafStorageGrant> {
        val persistableFlags = flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (persistableFlags == 0) {
            return storageFailure(StorageToolErrorCode.MissingGrant, "Picker result did not include a persistable grant")
        }

        return try {
            contentResolver.takePersistableUriPermission(uri, persistableFlags)
            val grant = listGrants().firstOrNull { it.uri == uri.toString() }
                ?: SafStorageGrant(
                    uri = uri.toString(),
                    canRead = persistableFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
                    canWrite = persistableFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
                    persistedAtMillis = System.currentTimeMillis(),
                )
            StorageToolResult.Success(grant)
        } catch (exception: SecurityException) {
            storageFailure(StorageToolErrorCode.MissingGrant, exception.message ?: "Unable to persist SAF grant")
        }
    }
}

@Singleton
class SafStorageTools @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val grantStore: SafStorageGrantStore,
    private val capabilityRegistry: SystemAccessCapabilityRegistry,
) {
    fun listGrants(): StorageToolResult<List<SafStorageGrant>> = withToolAccess(AndroidStorageToolIds.SafRead) {
        StorageToolResult.Success(grantStore.listGrants())
    }

    fun persistGrant(uri: Uri, flags: Int): StorageToolResult<SafStorageGrant> = grantStore.persistGrant(uri, flags)

    fun read(
        uri: Uri,
        maxBytes: Long = StorageAccessLimits.DEFAULT_MAX_READ_BYTES,
    ): StorageToolResult<StorageReadResponse> = withToolAccess(AndroidStorageToolIds.SafRead) {
        if (!grantStore.hasReadGrant(uri)) {
            return@withToolAccess storageFailure(StorageToolErrorCode.MissingGrant, "No persisted read grant for URI")
        }

        val safeMaxBytes = maxBytes.coerceIn(1, StorageAccessLimits.DEFAULT_MAX_READ_BYTES).toInt()
        try {
            val (content, truncated) = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readCapped(safeMaxBytes)
            } ?: return@withToolAccess storageFailure(StorageToolErrorCode.NotFound, "Unable to open URI")
            StorageToolResult.Success(
                StorageReadResponse(
                    path = uri.lastPathSegment ?: uri.toString(),
                    content = content,
                    bytesRead = content.size,
                    truncated = truncated,
                    sourceUri = uri.toString(),
                ),
            )
        } catch (exception: FileNotFoundException) {
            storageFailure(StorageToolErrorCode.NotFound, exception.message ?: "URI not found")
        } catch (exception: IOException) {
            storageFailure(StorageToolErrorCode.IoError, exception.message ?: "Unable to read URI")
        }
    }

    fun write(
        uri: Uri,
        content: ByteArray,
    ): StorageToolResult<StorageWriteResponse> = withToolAccess(AndroidStorageToolIds.SafWrite) {
        if (content.size > StorageAccessLimits.DEFAULT_MAX_WRITE_BYTES) {
            return@withToolAccess storageFailure(
                StorageToolErrorCode.TooLarge,
                "Write payload exceeds ${StorageAccessLimits.DEFAULT_MAX_WRITE_BYTES} bytes",
            )
        }
        if (!grantStore.hasWriteGrant(uri)) {
            return@withToolAccess storageFailure(StorageToolErrorCode.MissingGrant, "No persisted write grant for URI")
        }

        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> output.write(content) }
                ?: return@withToolAccess storageFailure(StorageToolErrorCode.NotFound, "Unable to open URI")
            StorageToolResult.Success(
                StorageWriteResponse(
                    path = uri.lastPathSegment ?: uri.toString(),
                    bytesWritten = content.size,
                    created = false,
                    destinationUri = uri.toString(),
                ),
            )
        } catch (exception: FileNotFoundException) {
            storageFailure(StorageToolErrorCode.NotFound, exception.message ?: "URI not found")
        } catch (exception: IOException) {
            storageFailure(StorageToolErrorCode.IoError, exception.message ?: "Unable to write URI")
        }
    }

    fun searchTree(
        uri: Uri,
        query: String,
    ): StorageToolResult<Nothing> = withToolAccess(AndroidStorageToolIds.SafSearch) {
        val boundedQuery = query.take(MAX_SAF_SEARCH_QUERY_LENGTH)
        if (!grantStore.hasReadGrant(uri)) {
            return@withToolAccess storageFailure(StorageToolErrorCode.MissingGrant, "No persisted read grant for URI")
        }
        storageFailure(
            StorageToolErrorCode.Unsupported,
            "SAF tree search is deferred; query '$boundedQuery' was not executed.",
        )
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
}
