package com.letta.mobile.runtime.local.modelcatalog

import android.content.Context
import com.letta.mobile.data.repository.api.ISettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

sealed interface EmbeddedModelDownloadState {
    data object Idle : EmbeddedModelDownloadState
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long?) : EmbeddedModelDownloadState
    data class Downloaded(val localPath: String) : EmbeddedModelDownloadState
    data class Failed(val message: String) : EmbeddedModelDownloadState
    data object Cancelled : EmbeddedModelDownloadState
}

fun shouldPublishEmbeddedModelProgress(
    previousBytesDownloaded: Long?,
    bytesDownloaded: Long,
    totalBytes: Long?,
): Boolean {
    val minDelta = totalBytes?.takeIf { it > 0L }?.let {
        (it / EMBEDDED_MODEL_PROGRESS_UPDATE_STEPS).coerceAtLeast(MIN_EMBEDDED_MODEL_PROGRESS_UPDATE_BYTES)
    } ?: MIN_EMBEDDED_MODEL_PROGRESS_UPDATE_BYTES
    return previousBytesDownloaded == null ||
        bytesDownloaded >= (totalBytes ?: Long.MAX_VALUE) ||
        bytesDownloaded - previousBytesDownloaded >= minDelta
}

fun sanitizeEmbeddedModelDownloadFailure(message: String?): String {
    val firstLine = message
        ?.lineSequence()
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return "Download failed."
    val lower = firstLine.lowercase()
    if (lower.contains("outofmemory") || lower.contains("out of memory") || lower.contains("failed to allocate")) {
        return "Download failed because the app ran out of memory. Please retry after closing other apps."
    }
    return firstLine.take(MAX_EMBEDDED_MODEL_FAILURE_MESSAGE_CHARS)
}

private const val MIN_EMBEDDED_MODEL_PROGRESS_UPDATE_BYTES = 1L * 1024L * 1024L
private const val EMBEDDED_MODEL_PROGRESS_UPDATE_STEPS = 100L
private const val MAX_EMBEDDED_MODEL_FAILURE_MESSAGE_CHARS = 160

data class EmbeddedModelCatalogItem(
    val entry: EmbeddedModelCatalogEntry,
    val state: EmbeddedModelDownloadState,
)

interface EmbeddedModelRepository {
    val catalog: StateFlow<List<EmbeddedModelCatalogItem>>
    suspend fun refresh()
    suspend fun download(entry: EmbeddedModelCatalogEntry)
    fun cancel(entry: EmbeddedModelCatalogEntry)
    fun localPathFor(entry: EmbeddedModelCatalogEntry): String?
}

@Singleton
class AssetEmbeddedModelRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: ISettingsRepository,
) : EmbeddedModelRepository {
    private val parser = EmbeddedModelCatalogParser()
    private val entries: List<EmbeddedModelCatalogEntry> by lazy {
        context.assets.open(CATALOG_ASSET).bufferedReader().use { parser.parse(it.readText()) }
    }
    private val downloadStates = mutableMapOf<String, MutableStateFlow<EmbeddedModelDownloadState>>()
    private val _catalog = MutableStateFlow<List<EmbeddedModelCatalogItem>>(emptyList())
    override val catalog: StateFlow<List<EmbeddedModelCatalogItem>> = _catalog.asStateFlow()

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        publishCatalog()
    }

    override suspend fun download(entry: EmbeddedModelCatalogEntry) = withContext(Dispatchers.IO) {
        require(entry.isSupported) { entry.unsupportedReason ?: "Model is not supported by the embedded runtime." }
        val url = entry.downloadUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No download URL is configured for ${entry.name}.")
        val targetDirectory = File(context.filesDir, MODEL_DIRECTORY).apply { mkdirs() }
        val target = File(targetDirectory, entry.modelFile)
        val temp = File(targetDirectory, "${entry.modelFile}.${System.nanoTime()}.tmp")
        if (entry.sizeInBytes > context.availableStorageBytes()) {
            throw IllegalStateException("Not enough storage is available for ${entry.name}.")
        }
        val state = stateFor(entry)
        state.value = EmbeddedModelDownloadState.Downloading(bytesDownloaded = 0L, totalBytes = entry.sizeInBytes)
        try {
            streamDownloadToFile(
                url = url,
                target = temp,
                expectedTotalBytes = entry.sizeInBytes,
                state = state,
            )
            if (!currentCoroutineContext().isActive || state.value == EmbeddedModelDownloadState.Cancelled) throw CancellationException()
            if (temp.length() == 0L) throw IllegalStateException("Downloaded model file is empty.")
            entry.checksumSha256?.takeIf { it.isNotBlank() }?.let { expected ->
                val actual = temp.sha256()
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException("Downloaded checksum did not match the catalog metadata.")
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            state.value = EmbeddedModelDownloadState.Downloaded(target.absolutePath)
        } catch (e: CancellationException) {
            temp.delete()
            state.value = EmbeddedModelDownloadState.Cancelled
            throw e
        } catch (e: Exception) {
            temp.delete()
            state.value = EmbeddedModelDownloadState.Failed(e.safeDownloadFailureMessage())
        } finally {
            publishCatalog()
        }
    }

    override fun cancel(entry: EmbeddedModelCatalogEntry) {
        stateFor(entry).value = EmbeddedModelDownloadState.Cancelled
        publishCatalog()
    }

    override fun localPathFor(entry: EmbeddedModelCatalogEntry): String? {
        val target = File(File(context.filesDir, MODEL_DIRECTORY), entry.modelFile)
        return target.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    private suspend fun streamDownloadToFile(
        url: String,
        target: File,
        expectedTotalBytes: Long?,
        state: MutableStateFlow<EmbeddedModelDownloadState>,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            huggingFaceTokenFor(url)?.let { token -> setRequestProperty("Authorization", "Bearer $token") }
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException(embeddedModelDownloadFailureMessage(url, status))
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: expectedTotalBytes
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloaded = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    while (currentCoroutineContext().isActive) {
                        if (state.value == EmbeddedModelDownloadState.Cancelled) throw CancellationException()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        publishProgress(state, downloaded, totalBytes)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun publishCatalog() {
        _catalog.value = entries.map { entry ->
            val downloadedPath = localPathFor(entry)
            val state = if (downloadedPath != null) {
                EmbeddedModelDownloadState.Downloaded(downloadedPath)
            } else {
                stateFor(entry).value
            }
            EmbeddedModelCatalogItem(entry = entry, state = state)
        }
    }

    private fun stateFor(entry: EmbeddedModelCatalogEntry): MutableStateFlow<EmbeddedModelDownloadState> =
        downloadStates.getOrPut(entry.id) { MutableStateFlow(EmbeddedModelDownloadState.Idle) }

    private fun publishProgress(
        state: MutableStateFlow<EmbeddedModelDownloadState>,
        downloaded: Long,
        totalBytes: Long?,
    ) {
        val previous = state.value as? EmbeddedModelDownloadState.Downloading
        if (shouldPublishEmbeddedModelProgress(previous?.bytesDownloaded, downloaded, totalBytes)) {
            state.value = EmbeddedModelDownloadState.Downloading(downloaded, totalBytes)
        }
    }

    private fun Throwable.safeDownloadFailureMessage(): String = sanitizeEmbeddedModelDownloadFailure(message)

    private fun huggingFaceTokenFor(url: String): String? =
        settingsRepository.huggingFaceToken.value?.takeIf { it.isNotBlank() && url.isHuggingFaceUrl() }

    private fun Context.availableStorageBytes(): Long = filesDir.usableSpace

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private const val CATALOG_ASSET = "embedded-models/model_allowlist.json"
        private const val MODEL_DIRECTORY = "embedded-lettacode/models"
    }
}
