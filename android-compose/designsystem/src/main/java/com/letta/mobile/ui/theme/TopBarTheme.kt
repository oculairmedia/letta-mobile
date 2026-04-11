package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

object LettaTopBarDefaults {
    @Composable
    fun scaffoldContainerColor() = MaterialTheme.colorScheme.surfaceContainer

    @Composable
    fun largeTopAppBarColors() = TopAppBarDefaults.largeTopAppBarColors(
        containerColor = scaffoldContainerColor(),
        scrolledContainerColor = scaffoldContainerColor(),
    )
}
