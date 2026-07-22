package com.letta.mobile.feature.chat.screen.messagelist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.feature.chat.screen.ChatMotion
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun ChatMessageListPinchIndicatorEffects(params: ChatMessageListPinchIndicatorEffectParams) {
    LaunchedEffect(params.pinchTick) {
        if (params.pinchTick > 0) {
            params.onShowFontIndicator(true)
            delay(1000.milliseconds)
            params.onShowFontIndicator(false)
        }
    }

    LaunchedEffect(params.pinchAnimationSuppressionTick) {
        if (params.pinchAnimationSuppressionTick > 0) {
            delay(ChatMotion.CONTENT_SIZE_MILLIS.toLong().milliseconds)
            if (!params.isPinching) {
                params.onSuppressPinchLayoutAnimations(false)
            }
        }
    }
}
