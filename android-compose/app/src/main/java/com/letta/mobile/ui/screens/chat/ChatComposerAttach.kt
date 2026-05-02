package com.letta.mobile.ui.screens.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Max long edge we send to the server in pixels. */
internal const val MAX_IMAGE_EDGE_PX = 1568

/** Target JPEG quality after downscale. */
internal const val JPEG_QUALITY = 85

/**
 * Hook up a PhotoPicker launcher that reads a picked URI from MediaStore,
 * decodes it, honours EXIF rotation, downscales to [MAX_IMAGE_EDGE_PX], re-encodes
 * as JPEG q=[JPEG_QUALITY], and hands a Base64-encoded [MessageContentPart.Image]
 * to [onPicked].
 *
 * Returns a `() -> Unit` that launches the picker when invoked.
 */
@Composable
fun rememberImageAttachmentPicker(
    onPicked: (MessageContentPart.Image) -> Unit,
    onError: (String) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        // Proof-of-callback trace. If this line does not appear in logcat
        // after the picker activity closes, the ActivityResult contract
        // never delivered the result to this Composable's launcher — the
        // most likely cause is composition destruction across process
        // death while DocumentsUI was foreground (see letta-mobile-jng2).
            Log.i(
                "ChatComposerAttach",
                "launcher.onResult uri=${if (uri == null) "<null>" else uri}",
            )
        if (uri == null) {
            Telemetry.event(
                "ChatComposerAttach",
                "attach.pickResult",
                "result" to "cancelled",
            )
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    loadAndNormalize(context, uri)
                }
            }.fold(
                onSuccess = {
                    Telemetry.event(
                        "ChatComposerAttach",
                        "attach.pickResult",
                        "result" to "decodedOk",
                        "mediaType" to it.mediaType,
                        "base64Len" to it.base64.length,
                    )
                    onPicked(it)
                },
                onFailure = {
                    val errorMessage = it.message ?: it.javaClass.simpleName
                    Telemetry.error(
                        "ChatComposerAttach",
                        "attach.pickResult",
                        it,
                        "result" to "decodeFailed",
                    )
                    Log.w("ChatComposerAttach", "loadAndNormalize failed", it)
                    onError(errorMessage)
                },
            )
        }
    }

    return remember(launcher) {
        {
            // Trace immediately before we hand off to the system picker,
            // so logcat gives us a "launch → result" bracket to reason
            // about even when the result callback never fires.
            Log.i("ChatComposerAttach", "launcher.launch() picker=PickVisualMedia.ImageOnly")
            Telemetry.event(
                "ChatComposerAttach",
                "attach.launch",
                "picker" to "PickVisualMedia.ImageOnly",
            )
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

@VisibleForTesting
internal fun loadAndNormalize(context: Context, uri: Uri): MessageContentPart.Image {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("openInputStream returned null for $uri")
    val bytes = input.use { it.readBytes() }

    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("Could not decode image")

    val rotation = runCatching {
        ExifInterface(bytes.inputStream()).rotationDegrees.toFloat()
    }.getOrDefault(0f)

    val rotated = if (rotation != 0f) {
        Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(rotation) },
            true,
        )
    } else {
        bitmap
    }

    val scaled = scaleToMaxEdge(rotated, MAX_IMAGE_EDGE_PX)
    val out = ByteArrayOutputStream(128 * 1024)
    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    val base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    return MessageContentPart.Image(base64 = base64, mediaType = "image/jpeg")
}

private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(src.width, src.height)
    if (longest <= maxEdge) return src
    val scale = maxEdge.toFloat() / longest
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true)
}

private val ExifInterface.rotationDegrees: Int
    get() = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
