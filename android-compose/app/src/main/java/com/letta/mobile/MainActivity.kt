package com.letta.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.SettingsRepository
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.SnackbarDispatcher
import com.letta.mobile.ui.navigation.AppNavGraph
import com.letta.mobile.ui.theme.LettaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val snackbarDispatcher = remember { SnackbarDispatcher() }
            val snackbarHostState = remember { SnackbarHostState() }
            val appTheme = settingsRepository.getTheme().collectAsState(initial = AppTheme.SYSTEM)
            val themePreset = settingsRepository.getThemePreset().collectAsState(initial = ThemePreset.DEFAULT)
            val amoledDarkMode = settingsRepository.getAmoledDarkMode().collectAsState(initial = false)

            LaunchedEffect(Unit) {
                snackbarDispatcher.messages.collect { message ->
                    val result = snackbarHostState.showSnackbar(
                        message = message.message,
                        actionLabel = message.actionLabel,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        message.onAction?.invoke()
                    }
                }
            }

            LettaTheme(
                appTheme = appTheme.value,
                themePreset = themePreset.value,
                amoledDarkMode = amoledDarkMode.value,
            ) {
                CompositionLocalProvider(LocalSnackbarDispatcher provides snackbarDispatcher) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                    ) { _ ->
                        AppNavGraph()
                    }
                }
            }
        }
    }
}
