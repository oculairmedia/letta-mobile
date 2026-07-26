package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.SnackbarDuration
import com.letta.mobile.ui.chat.render.ChatSnackbarDuration

internal fun ChatSnackbarDuration.toMaterialDuration(): SnackbarDuration = when (this) {
    ChatSnackbarDuration.Short -> SnackbarDuration.Short
    ChatSnackbarDuration.Indefinite -> SnackbarDuration.Indefinite
}
