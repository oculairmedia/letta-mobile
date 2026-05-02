package com.letta.mobile

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import com.letta.mobile.ui.navigation.ShareToAgentRoute

/**
 * Launch target for Android Sharesheet ACTION_SEND/ACTION_SEND_MULTIPLE intents.
 *
 * Shared text and links are forwarded directly. Stream shares are represented
 * by their content URI until first-class shared attachment routing exists.
 */
data class ShareLaunchTarget(
    val sharedText: String,
    private val requestId: Long = System.currentTimeMillis(),
) : AppLaunchTarget {
    override fun toRoute(): Any = ShareToAgentRoute(sharedText = sharedText)

    companion object {
        private const val MAX_SHARED_TEXT_LENGTH = 16_000

        fun fromIntent(intent: Intent?): ShareLaunchTarget? {
            if (intent == null) return null
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null

            val payload = intent.toSharedText().trim().takeIf { it.isNotBlank() } ?: return null
            return ShareLaunchTarget(sharedText = payload.truncateForRoute())
        }

        private fun Intent.toSharedText(): String {
            val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val subject = getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
            val title = getStringExtra(Intent.EXTRA_TITLE)?.trim().orEmpty()
            val header = subject.ifBlank { title }
                .takeIf { it.isNotBlank() && it != text }

            val streamText = streamUris().takeIf { it.isNotEmpty() }?.joinToString(separator = "\n") { uri ->
                val mime = type?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                "- $uri$mime"
            }?.let { uris ->
                val label = if (action == Intent.ACTION_SEND_MULTIPLE) "Shared content" else "Shared file"
                "$label:\n$uris"
            }.orEmpty()

            return listOfNotNull(
                header,
                text.takeIf { it.isNotBlank() },
                streamText.takeIf { it.isNotBlank() },
            ).joinToString(separator = "\n\n")
        }

        private fun Intent.streamUris(): List<Uri> {
            return if (action == Intent.ACTION_SEND_MULTIPLE) {
                getParcelableArrayListCompat(Intent.EXTRA_STREAM)
            } else {
                getParcelableCompat<Uri>(Intent.EXTRA_STREAM)?.let(::listOf).orEmpty()
            }
        }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Parcelable> Intent.getParcelableCompat(key: String): T? {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, T::class.java)
            } else {
                getParcelableExtra(key) as? T
            }
        }

        @Suppress("DEPRECATION")
        private inline fun <reified T : Parcelable> Intent.getParcelableArrayListCompat(key: String): List<T> {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelableArrayListExtra(key, T::class.java).orEmpty()
            } else {
                getParcelableArrayListExtra<T>(key).orEmpty()
            }
        }

        private fun String.truncateForRoute(): String {
            return if (length <= MAX_SHARED_TEXT_LENGTH) {
                this
            } else {
                take(MAX_SHARED_TEXT_LENGTH) + "\n\n[Shared content truncated for Letta Mobile.]"
            }
        }
    }
}
