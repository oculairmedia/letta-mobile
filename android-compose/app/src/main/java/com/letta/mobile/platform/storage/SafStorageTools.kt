package com.letta.mobile.platform.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
