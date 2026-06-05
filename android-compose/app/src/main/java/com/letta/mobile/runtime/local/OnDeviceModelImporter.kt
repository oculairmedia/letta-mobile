package com.letta.mobile.runtime.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImportedOnDeviceModel(
    val displayName: String,
    val fileName: String,
    val path: String,
    val handle: String,
)

interface OnDeviceModelImporter {
    suspend fun importModel(uri: Uri): ImportedOnDeviceModel
}

@Singleton
class SafOnDeviceModelImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : OnDeviceModelImporter {
    override suspend fun importModel(uri: Uri): ImportedOnDeviceModel = withContext(Dispatchers.IO) {
        val displayName = context.displayNameFor(uri)
        val fileName = sanitizeOnDeviceModelFileName(displayName)
        val targetDirectory = File(context.filesDir, MODEL_DIRECTORY).apply { mkdirs() }
        val target = targetDirectory.uniqueChild(fileName)
        val temp = File(targetDirectory, "${target.name}.${System.nanoTime()}.tmp")

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the selected model file.")
        input.use { source ->
            temp.outputStream().use { output -> source.copyTo(output) }
        }
        if (temp.length() == 0L) {
            temp.delete()
            throw IllegalStateException("The selected model file is empty.")
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = false)
            temp.delete()
        }

        ImportedOnDeviceModel(
            displayName = displayName,
            fileName = target.name,
            path = target.absolutePath,
            handle = onDeviceModelHandleFor(target.name),
        )
    }

    private fun Context.displayNameFor(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_FILE_NAME
            }
        }
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL_FILE_NAME
    }

    private companion object {
        private const val MODEL_DIRECTORY = "embedded-lettacode/models"
        private const val DEFAULT_MODEL_FILE_NAME = "model.litertlm"
    }
}

fun sanitizeOnDeviceModelFileName(rawName: String): String {
    val withoutPath = rawName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    val sanitized = withoutPath
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.', '-')
    return sanitized.takeIf { it.isNotBlank() } ?: "model.litertlm"
}

fun onDeviceModelHandleFor(fileName: String): String {
    val stem = fileName.substringBeforeLast('.', fileName)
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .replace(Regex("[-_.]+"), "-")
        .trim('-')
        .takeIf { it.isNotBlank() }
        ?: "default"
    return "local/$stem"
}

private fun File.uniqueChild(fileName: String): File {
    val stem = fileName.substringBeforeLast('.', fileName)
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf { it.isNotBlank() && it != fileName }
        ?.let { ".$it" }
        .orEmpty()
    var candidate = File(this, fileName)
    var index = 2
    while (candidate.exists()) {
        candidate = File(this, "$stem-$index$extension")
        index += 1
    }
    return candidate
}
