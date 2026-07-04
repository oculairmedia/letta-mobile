package com.letta.mobile.desktop.avatar.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The speech bubble overlaid INSIDE the pet window (PRD §5 B1, §6 SPEAKING):
 * top area, max width ~300dp, streaming the assistant reply text as it arrives.
 * Non-interactive v1 — it's a passive read-out of [text]. The caller owns
 * visibility (show while streaming, auto-hide ~4s after the stream ends).
 */
@Composable
fun PetSpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank()) return
    Box(
        modifier = modifier
            .widthIn(max = 300.dp)
            .background(Color(0xF2202027), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x2AFFFFFF), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = Color(0xFFF0F0F3), fontSize = 12.sp, lineHeight = 16.sp),
            // Cap the streamed read-out so a long reply can't grow past the pet
            // window; the full text lives in the chat, this is a glance.
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
