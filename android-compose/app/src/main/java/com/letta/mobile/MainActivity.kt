package com.letta.mobile

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.SettingsRepository
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
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

    private var notificationTarget by mutableStateOf<NotificationNavigationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        notificationTarget = NotificationNavigationTarget.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            val snackbarDispatcher = remember { SnackbarDispatcher() }
            val snackbarHostState = remember { SnackbarHostState() }
            val appTheme = settingsRepository.getTheme().collectAsState(initial = AppTheme.SYSTEM)
            val themePreset = settingsRepository.getThemePreset().collectAsState(initial = ThemePreset.DEFAULT)
            val dynamicColor = settingsRepository.getDynamicColor().collectAsState(initial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {},
            )

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

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            LettaTheme(
                appTheme = appTheme.value,
                themePreset = themePreset.value,
                dynamicColor = dynamicColor.value,
            ) {
                CompositionLocalProvider(LocalSnackbarDispatcher provides snackbarDispatcher) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                    ) { _ ->
                        AppNavGraph(
                            notificationTarget = notificationTarget,
                            onNotificationTargetConsumed = { notificationTarget = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTarget = NotificationNavigationTarget.fromIntent(intent)
    }
}
