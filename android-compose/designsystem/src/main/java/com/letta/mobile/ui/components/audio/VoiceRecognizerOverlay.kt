package com.letta.mobile.ui.components.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * letta-mobile-arhd: full-screen visual context for an in-flight
 * speech recognition session. The Edge Gallery original wraps
 * [AudioAnimation] inside this same shell — without it the shader is
 * orphaned (no scrim, no transcript, no instruction labels) and the
 * fade-to-dark uniform tuning makes the gradient look washed out.
 *
 * Designed to be mounted as a sibling overlay (NOT a Dialog or Popup)
 * so the [HoldToDictateButton]'s pointer-input tracking on the
 * underlying composer keeps receiving events. The overlay's scrim
 * intentionally omits any pointer-consuming modifier so taps pass
 * through to the button beneath.
 *
 * The bottom instruction pill IS pointer-eating
 * (`Modifier.pointerInput(Unit) {}`) so an accidental tap there can't
 * fall through to the chat list and trigger a row click while the
 * user is mid-dictation.
 */
@Composable
fun VoiceRecognizerOverlay(
    visible: Boolean,
    recognizedText: String,
    amplitude: Int,
    modifier: Modifier = Modifier,
    listeningLabel: String = "Listening…",
    instructionLabel: String = "Slide up to cancel · release to send",
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Shader fills the bottom two thirds; the upper third fades
            // to scrim per the shader's hardcoded fade_start/fade_end.
            AudioAnimation(
                bgColor = Color.Black.copy(alpha = 0.85f),
                amplitude = amplitude,
                modifier = Modifier.fillMaxSize(),
            )

            // Recognized partial transcription — centered, white-on-dark.
            Text(
                text = recognizedText.ifBlank { " " },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            )

            // Bottom instruction pill — pointer-eating so accidental
            // taps don't fall through to the underlying chat content
            // while the user is mid-dictation.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                    .pointerInput(Unit) { /* eat all pointer events here */ },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                        )
                        Text(
                            text = listeningLabel,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Text(
                        text = instructionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
