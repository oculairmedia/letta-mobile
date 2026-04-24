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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.letta.mobile.channel.ChatPushService
import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.repository.SettingsRepository
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.SnackbarDispatcher
import com.letta.mobile.ui.navigation.AdaptiveScaffold
import com.letta.mobile.ui.navigation.AppNavGraph
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.LettaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var crashReporter: CrashReporter

    private var launchTarget by mutableStateOf<AppLaunchTarget?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        launchTarget = AppLaunchTarget.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
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

            // Surface any uncaught crash from the previous session exactly once.
            // Snackbar with a "Copy id" action lets the user file a ticket tied
            // to the Sentry event. Dismissing clears the on-disk record.
            val lastCrash by crashReporter.lastCrash.collectAsState()
            LaunchedEffect(lastCrash) {
                val crash = lastCrash ?: return@LaunchedEffect
                val label = if (crash.sentryEventId != null) "Copy id" else "Details"
                val summary = "App crashed last run (${crash.type.substringAfterLast('.')}). Tap to copy."
                val result = snackbarHostState.showSnackbar(
                    message = summary,
                    actionLabel = label,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val payload = crash.sentryEventId
                        ?: "${crash.type}: ${crash.message}\n${crash.stackHead}"
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("letta-mobile crash", payload))
                    Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                crashReporter.dismiss()
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // letta-mobile-mge5: start the foreground service that keeps
                // resume-stream subscribers alive even when the app is
                // backgrounded or the screen is off.
                ChatPushService.start(this@MainActivity)
            }

            LettaTheme(
                appTheme = appTheme.value,
                themePreset = themePreset.value,
                dynamicColor = dynamicColor.value,
            ) {
                CompositionLocalProvider(
                    LocalSnackbarDispatcher provides snackbarDispatcher,
                    LocalWindowSizeClass provides windowSizeClass,
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                    ) { _ ->
                        val navController = rememberNavController()
                        AdaptiveScaffold(navController = navController) {
                            AppNavGraph(
                                navController = navController,
                                 notificationTarget = launchTarget,
                                 onNotificationTargetConsumed = { launchTarget = null },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchTarget = AppLaunchTarget.fromIntent(intent)
    }
}
