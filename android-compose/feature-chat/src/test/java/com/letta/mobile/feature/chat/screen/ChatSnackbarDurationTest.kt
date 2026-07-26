package com.letta.mobile.feature.chat.screen

import androidx.compose.material3.SnackbarDuration
import com.letta.mobile.ui.chat.render.ChatSnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSnackbarDurationTest {
    @Test
    fun sharedDurationsMapToMaterialDurations() {
        assertEquals(SnackbarDuration.Short, ChatSnackbarDuration.Short.toMaterialDuration())
        assertEquals(SnackbarDuration.Indefinite, ChatSnackbarDuration.Indefinite.toMaterialDuration())
    }
}
