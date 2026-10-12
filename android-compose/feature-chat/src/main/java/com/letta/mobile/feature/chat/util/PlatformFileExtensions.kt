package com.letta.mobile.feature.chat.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.feature.chat.R
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val PlatformFile.uri: Uri
    get() = when (val wrapped = this.androidFile) {
        is AndroidFile.UriWrapper -> wrapped.uri
        is AndroidFile.FileWrapper -> Uri.fromFile(wrapped.file)
        else -> error("Unknown AndroidFile type")
    }

/** The shared image viewer's Save: MediaStore under Pictures/Letta, with a toast either way. */
internal suspend fun saveAttachment(context: Context, attachment: UiImageAttachment) {
    val saved = withContext(Dispatchers.IO) { saveAttachmentToMediaStore(context, attachment) }
    context.toast(
        if (saved) {
            context.getString(R.string.screen_chat_image_saved)
        } else {
            context.getString(R.string.screen_chat_image_save_error)
        },
    )
}

/** The shared image viewer's Share: the share sheet through the chat-image FileProvider. */
internal suspend fun shareAttachment(context: Context, attachment: UiImageAttachment) {
    val uri = withContext(Dispatchers.IO) { writeAttachmentForSharing(context, attachment) }
    if (uri == null) {
        context.toast(context.getString(R.string.screen_chat_image_share_error))
        return
    }
    context.startActivity(
        Intent.createChooser(
            attachment.shareIntent(uri),
            context.getString(R.string.screen_chat_image_share_chooser),
        ),
    )
}

private fun saveAttachmentToMediaStore(context: Context, attachment: UiImageAttachment): Boolean {
    val bytes = attachment.decodeBytesOrNull() ?: return false
    val resolver = context.contentResolver
    var uri: Uri? = null
    return runCatching {
        val filename = attachment.imageFilename()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, attachment.mediaType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Letta")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val imageUri = uri ?: return false
        resolver.openOutputStream(imageUri)?.use { output -> output.write(bytes) }
            ?: return false
        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }.also { completeValues ->
            resolver.update(imageUri, completeValues, null, null)
        }
        true
    }.getOrElse {
        uri?.let { imageUri -> runCatching { resolver.delete(imageUri, null, null) } }
        false
    }
}

private fun writeAttachmentForSharing(context: Context, attachment: UiImageAttachment): Uri? = runCatching {
    val bytes = attachment.decodeBytesOrNull() ?: return null
    val imageDir = File(context.cacheDir, "shared-chat-images").apply { mkdirs() }
    val imageFile = File(imageDir, attachment.imageFilename())
    imageFile.writeBytes(bytes)
    FileProvider.getUriForFile(context, "${context.packageName}.chat-image-share", imageFile)
}.getOrNull()

private fun UiImageAttachment.shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = mediaType
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun UiImageAttachment.decodeBytesOrNull(): ByteArray? = runCatching {
    Base64.decode(base64, Base64.DEFAULT)
}.getOrNull()

private fun UiImageAttachment.imageFilename(): String =
    "letta-image-${System.currentTimeMillis()}.${mediaType.fileExtension()}"

private fun String.fileExtension(): String = when (substringAfter('/').substringBefore(';').lowercase()) {
    "jpeg" -> "jpg"
    "png" -> "png"
    "webp" -> "webp"
    "gif" -> "gif"
    else -> "png"
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
