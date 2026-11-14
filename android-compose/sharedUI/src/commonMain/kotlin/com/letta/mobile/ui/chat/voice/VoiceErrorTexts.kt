package com.letta.mobile.ui.chat.voice

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.voice_error_audio
import com.letta.mobile.sharedui.resources.voice_error_generic
import com.letta.mobile.sharedui.resources.voice_error_insecure
import com.letta.mobile.sharedui.resources.voice_error_invalid
import com.letta.mobile.sharedui.resources.voice_error_not_delivered
import com.letta.mobile.sharedui.resources.voice_error_rejected
import com.letta.mobile.sharedui.resources.voice_error_run_failed
import com.letta.mobile.sharedui.resources.voice_error_timed_out
import com.letta.mobile.sharedui.resources.voice_error_unattributed
import com.letta.mobile.sharedui.resources.voice_error_unauthorized
import com.letta.mobile.sharedui.resources.voice_error_unreachable
import org.jetbrains.compose.resources.StringResource

private val audioFailures = setOf(
    VoiceErrorCode.UNAVAILABLE,
    VoiceErrorCode.PREPARATION_FAILED,
    VoiceErrorCode.PLAYBACK_FAILED,
    VoiceErrorCode.UNSUPPORTED_FORMAT,
    VoiceErrorCode.BUSY,
)

private val errorTexts = mapOf(
    VoiceErrorCode.UNREACHABLE to Res.string.voice_error_unreachable,
    VoiceErrorCode.TIMED_OUT to Res.string.voice_error_timed_out,
    VoiceErrorCode.UNAUTHORIZED to Res.string.voice_error_unauthorized,
    VoiceErrorCode.INSECURE_TRANSPORT to Res.string.voice_error_insecure,
    VoiceErrorCode.INVALID_ENDPOINT to Res.string.voice_error_invalid,
    VoiceErrorCode.REJECTED to Res.string.voice_error_rejected,
    VoiceErrorCode.NOT_DELIVERED to Res.string.voice_error_not_delivered,
    VoiceErrorCode.UNATTRIBUTED to Res.string.voice_error_unattributed,
    VoiceErrorCode.RUN_FAILED to Res.string.voice_error_run_failed,
)

/** The plain-language sentence for a voice failure [code]; shared by the strip and the Voice settings. */
fun voiceErrorText(code: VoiceErrorCode): StringResource = errorTexts[code]
    ?: if (code in audioFailures) Res.string.voice_error_audio else Res.string.voice_error_generic
