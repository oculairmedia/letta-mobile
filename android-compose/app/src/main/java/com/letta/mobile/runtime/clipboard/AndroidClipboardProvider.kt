package com.letta.mobile.runtime.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import javax.inject.Inject

class AndroidClipboardProvider @Inject constructor(
    context: Context,
) : ClipboardProvider {
    private val appContext = context.applicationContext

    override fun readText(): ClipboardReadResponse {
        return runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) {
                return ClipboardReadResponse(
                    success = true,
                    text = null,
                    reason = "Clipboard is empty.",
                )
            }
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                return ClipboardReadResponse(
                    success = true,
                    text = null,
                    reason = "Clipboard has no items.",
                )
            }
            val text = clip.getItemAt(0)?.text?.toString()
            ClipboardReadResponse(
                success = true,
                text = text,
                reason = if (text != null) "Clipboard text retrieved." else "Clipboard item has no text content.",
            )
        }.getOrElse { error ->
            ClipboardReadResponse(
                success = false,
                text = null,
                reason = error.message ?: "Failed to read clipboard.",
            )
        }
    }

    override fun writeText(text: String): ClipboardWriteResponse {
        return runCatching {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("letta-mobile-clipboard", text)
            clipboard.setPrimaryClip(clip)
            ClipboardWriteResponse(
                success = true,
                reason = "Clipboard text set successfully.",
            )
        }.getOrElse { error ->
            ClipboardWriteResponse(
                success = false,
                reason = error.message ?: "Failed to write clipboard.",
            )
        }
    }
}
