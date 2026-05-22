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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.debug.AutomationAuthBootstrap
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
import androidx.core.app.ActivityCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settingsRepository: ISettingsRepository

    @Inject
    lateinit var crashReporter: CrashReporter

    private var launchTarget by mutableStateOf<AppLaunchTarget?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ProfileCaptureKeyguardHelper.allowProfileCaptureLaunch(this)
        importAutomationPayloadFromLaunchIntent()
        launchTarget = AppLaunchTarget.fromIntent(intent)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
            val snackbarDispatcher = remember { SnackbarDispatcher() }
            val snackbarHostState = remember { SnackbarHostState() }
            val appTheme by settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = AppTheme.SYSTEM)
            val themePreset by settingsRepository.getThemePreset().collectAsStateWithLifecycle(initialValue = ThemePreset.DEFAULT)
            val dynamicColor by settingsRepository.getDynamicColor().collectAsStateWithLifecycle(initialValue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted ->
                    if (granted) {
                        ChatPushService.start(this@MainActivity)
                    }
                },
            )

            LaunchedEffect(Unit) {
                snackbarDispatcher.messages.collect { message ->
                    val result = snackbarHostState.showSnackbar(
                        message = message.message,
                        actionLabel = message.actionLabel,
                        duration = message.duration,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        message.onAction?.invoke()
                    }
                }
            }

            // Surface any uncaught crash from the previous session exactly once.
            // Snackbar with a "Copy id" action lets the user file a ticket tied
            // to the Sentry event. Dismissing clears the on-disk record.
            val lastCrash by crashReporter.lastCrash.collectAsStateWithLifecycle()
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
                    return@LaunchedEffect
                }
                // letta-mobile-mge5: start the foreground service that keeps
                // resume-stream subscribers alive even when the app is
                // backgrounded or the screen is off.
                ChatPushService.start(this@MainActivity)
            }

            LettaTheme(
                appTheme = appTheme,
                themePreset = themePreset,
                dynamicColor = dynamicColor,
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
        // Report that the app is fully interactive. This signals Android Vitals
        // (Play Console) for accurate cold-start measurement beyond TTID. Called
        // once — subsequent calls on the same activity are no-ops.
        reportFullyDrawn()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importAutomationPayloadFromLaunchIntent()
        launchTarget = AppLaunchTarget.fromIntent(intent)
    }

    private fun importAutomationPayloadFromLaunchIntent() {
        val encodedPayload = intent.getStringExtra(AutomationAuthBootstrap.EXTRA_PAYLOAD_BASE64)
            ?.trim()
            .orEmpty()
        if (encodedPayload.isBlank()) {
            return
        }
        getSharedPreferences(AutomationAuthBootstrap.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, encodedPayload)
            .commit()
        AutomationAuthBootstrap.importPendingConfig(this, settingsRepository)
        intent.removeExtra(AutomationAuthBootstrap.EXTRA_PAYLOAD_BASE64)
    }
}
