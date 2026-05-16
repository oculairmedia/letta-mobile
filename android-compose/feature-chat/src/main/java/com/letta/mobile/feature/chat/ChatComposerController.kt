package com.letta.mobile.feature.chat

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Legacy aliases for [AttachmentLimits.Default]. Prefer the injected
 * [AttachmentLimits] for per-call configurability.
 */
@Deprecated(
    "Read from injected AttachmentLimits.maxAttachmentCount instead.",
    ReplaceWith("AttachmentLimits.Default.maxAttachmentCount"),
)
const val MAX_COMPOSER_ATTACHMENTS = 4

@Deprecated(
    "Read from injected AttachmentLimits.maxTotalBase64Bytes instead.",
    ReplaceWith("AttachmentLimits.Default.maxTotalBase64Bytes"),
)
const val MAX_COMPOSER_TOTAL_BYTES = 8 * 1024 * 1024

@Immutable
data class ChatComposerState(
    val inputText: String = "",
    val pendingAttachments: ImmutableList<MessageContentPart.Image> = persistentListOf(),
    val inputHistory: ImmutableList<String> = persistentListOf(),
    val error: String? = null,
) {
    val hasSendableContent: Boolean
        get() = inputText.isNotBlank() || pendingAttachments.isNotEmpty()
}

data class ComposerSendPayload(
    val text: String,
    val attachments: List<MessageContentPart.Image>,
)

sealed interface ChatComposerEffect {
    data object OpenBugReport : ChatComposerEffect
}

enum class ChatSlashCommand {
    Bug,
}

object ChatSlashCommandParser {
    fun parse(
        text: String,
        projectContextAvailable: Boolean,
    ): ChatSlashCommand? {
        if (!projectContextAvailable) return null
        return when (text.trim()) {
            "/bug" -> ChatSlashCommand.Bug
            else -> null
        }
    }
}

fun interface ChatComposerTelemetry {
    fun event(name: String, vararg attrs: Pair<String, Any?>)
}

class ChatComposerController(
    private val telemetry: ChatComposerTelemetry = ChatComposerTelemetry { name, attrs ->
        Telemetry.event("AdminChatVM", name, *attrs)
    },
    private val limits: AttachmentLimits = AttachmentLimits.Default,
) {
    private val _state = MutableStateFlow(ChatComposerState())
    val state: StateFlow<ChatComposerState> = _state.asStateFlow()

    fun updateText(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun clearText() {
        if (_state.value.inputText.isEmpty()) return
        _state.update { it.copy(inputText = "") }
    }

    fun setError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() {
        if (_state.value.error == null) return
        _state.update { it.copy(error = null) }
    }

    /**
     * Stage a new image attachment in the composer. Enforces the
     * [AttachmentLimits.maxAttachmentCount] count cap and
     * [AttachmentLimits.maxTotalBase64Bytes] cumulative base64 size
     * cap. Returns true on success; false if a cap was hit.
     */
    fun addAttachment(image: MessageContentPart.Image): Boolean {
        val current = _state.value.pendingAttachments
        if (current.size >= limits.maxAttachmentCount) {
            telemetry.event(
                "attachment.rejected",
                "reason" to "count_cap",
                "current" to current.size,
                "max" to limits.maxAttachmentCount,
            )
            setError("Attachment limit reached (${limits.maxAttachmentCount} max).")
            return false
        }
        val newTotal = current.sumOf { it.base64.length } + image.base64.length
        if (newTotal > limits.maxTotalBase64Bytes) {
            telemetry.event(
                "attachment.rejected",
                "reason" to "size_cap",
                "newTotal" to newTotal,
                "max" to limits.maxTotalBase64Bytes,
            )
            setError("Attachments too large — downscale or remove some before sending.")
            return false
        }
        _state.update {
            it.copy(
                pendingAttachments = (current + image).toPersistentList(),
                error = null,
            )
        }
        telemetry.event(
            "attachment.added",
            "size" to image.base64.length,
            "mediaType" to image.mediaType,
            "totalCount" to (current.size + 1),
        )
        return true
    }

    /** Remove a staged attachment by index. */
    fun removeAttachment(index: Int) {
        val current = _state.value.pendingAttachments
        if (index !in current.indices) return
        _state.update {
            it.copy(
                pendingAttachments = current.toMutableList()
                    .also { attachments -> attachments.removeAt(index) }
                    .toPersistentList(),
            )
        }
        telemetry.event(
            "attachment.removed",
            "index" to index,
        )
    }

    fun payloadForSend(text: String = _state.value.inputText): ComposerSendPayload? {
        val attachments = _state.value.pendingAttachments.toList()
        if (text.isBlank() && attachments.isEmpty()) return null
        return ComposerSendPayload(text = text, attachments = attachments)
    }

    fun clearAfterSend() {
        _state.update { current ->
            val trimmed = current.inputText.trim()
            val nextHistory = if (trimmed.isNotBlank()) {
                (listOf(trimmed) + current.inputHistory.filterNot { it == trimmed })
                    .take(30)
                    .toPersistentList()
            } else {
                current.inputHistory
            }
            current.copy(
                inputText = "",
                pendingAttachments = persistentListOf(),
                inputHistory = nextHistory,
                error = null,
            )
        }
    }
}
