package com.letta.mobile.ui.screens.chat

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Upper bound on composer image attachments per message. */
const val MAX_COMPOSER_ATTACHMENTS = 4

/** Upper bound on per-message total base64 payload (approximate — ~8 MB). */
const val MAX_COMPOSER_TOTAL_BYTES = 8 * 1024 * 1024

@Immutable
data class ChatComposerState(
    val inputText: String = "",
    val pendingAttachments: ImmutableList<MessageContentPart.Image> = persistentListOf(),
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
     * Stage a new image attachment in the composer. Enforces [MAX_COMPOSER_ATTACHMENTS]
     * count cap and [MAX_COMPOSER_TOTAL_BYTES] cumulative base64 size cap. Returns
     * true on success; false if a cap was hit.
     */
    fun addAttachment(image: MessageContentPart.Image): Boolean {
        val current = _state.value.pendingAttachments
        if (current.size >= MAX_COMPOSER_ATTACHMENTS) {
            telemetry.event(
                "attachment.rejected",
                "reason" to "count_cap",
                "current" to current.size,
                "max" to MAX_COMPOSER_ATTACHMENTS,
            )
            setError("Attachment limit reached ($MAX_COMPOSER_ATTACHMENTS max).")
            return false
        }
        val newTotal = current.sumOf { it.base64.length } + image.base64.length
        if (newTotal > MAX_COMPOSER_TOTAL_BYTES) {
            telemetry.event(
                "attachment.rejected",
                "reason" to "size_cap",
                "newTotal" to newTotal,
                "max" to MAX_COMPOSER_TOTAL_BYTES,
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
        _state.update {
            it.copy(
                inputText = "",
                pendingAttachments = persistentListOf(),
                error = null,
            )
        }
    }
}
