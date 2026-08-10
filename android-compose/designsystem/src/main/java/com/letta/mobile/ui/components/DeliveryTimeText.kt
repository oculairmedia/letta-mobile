package com.letta.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.scaledBy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DeliveryTimeText(
    timestamp: String,
    modifier: Modifier = Modifier,
) {
    val text = remember(timestamp) { timestamp.toDeliveryTimeText() } ?: return

    Text(
        text = text,
        modifier = modifier.alpha(0.5f),
        style = MaterialTheme.typography.labelSmall.scaledBy(LocalChatFontScale.current),
    )
}

private val deliveryTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun String.toDeliveryTimeText(): String? = runCatching {
    Instant.parse(this)
        .atZone(ZoneId.systemDefault())
        .format(deliveryTimeFormatter)
}.getOrNull()
