package com.letta.mobile.ui.screens.plugins

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import com.letta.mobile.data.plugin.catalog.PluginPackageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * letta-mobile-cdgpr.9: Android's file picker for the shared Plugins page. Opens the system document
 * picker through the activity's result registry and reads the chosen package off the main thread,
 * at most [MAX_READ_BYTES] (the page refuses anything larger). No UI here: the page is sharedUI's.
 */
class AndroidPluginPackagePicker(
    private val registry: ActivityResultRegistry,
    private val resolver: ContentResolver,
) {
    /** The chosen package, or null when the owner backed out of the picker. */
    suspend fun pick(): PluginPackageFile? {
        val uri = awaitDocument() ?: return null
        return withContext(Dispatchers.IO) { PluginPackageFile(displayName(uri), readBounded(uri)) }
    }

    private suspend fun awaitDocument(): Uri? = suspendCancellableCoroutine { continuation ->
        lateinit var launcher: ActivityResultLauncher<Array<String>>
        launcher = registry.register("plugin-package-${keys.incrementAndGet()}", ActivityResultContracts.OpenDocument()) { uri ->
            launcher.unregister()
            if (continuation.isActive) continuation.resume(uri)
        }
        continuation.invokeOnCancellation { launcher.unregister() }
        launcher.launch(MIME_TYPES)
    }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        } ?: uri.lastPathSegment ?: FALLBACK_NAME

    private fun readBounded(uri: Uri): ByteArray {
        val out = ByteArrayOutputStream()
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (out.size() <= MAX_READ_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private companion object {
        val keys = AtomicInteger()
        val MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")
        const val FALLBACK_NAME = "plugin package"
        const val BUFFER_BYTES = 64 * 1024

        /** One byte over the page's 16 MiB limit, so the page can say the file is too large. */
        const val MAX_READ_BYTES = 16 * 1024 * 1024 + 1
    }
}
