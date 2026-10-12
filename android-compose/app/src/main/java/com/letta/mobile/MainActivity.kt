package com.letta.mobile

import android.Manifest
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.ui.mascot.ProvideMascotShell
import com.letta.mobile.ui.notify.LocalAttentionPrefs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import com.letta.mobile.channel.ChatPushService
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.debug.AutomationAuthBootstrap
import com.letta.mobile.debug.TouchDispatchDiagnostics
import com.letta.mobile.debug.debugRootPointerObserver
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.navigation.compose.rememberNavController
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.SnackbarDispatcher
import com.letta.mobile.ui.common.toOverlay
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayAction
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.OverlayHostRoot
import com.letta.mobile.ui.host.OverlayPresentation
import com.letta.mobile.ui.host.OverlayResponse
import com.letta.mobile.ui.host.rememberOverlayHost
import com.letta.mobile.ui.navigation.AdaptiveScaffold
import com.letta.mobile.ui.navigation.AppNavGraph
import com.letta.mobile.ui.theme.LocalWindowSizeClass
import com.letta.mobile.ui.theme.LettaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * Single field inject required by `@AndroidEntryPoint`. Collaborators live
     * on [MainActivityDependencies] via constructor injection.
     */
    @Inject
    lateinit var deps: MainActivityDependencies

    private val settingsRepository get() = deps.settingsRepository
    private val crashReporter get() = deps.crashReporter

    private val launchTarget = mutableStateOf<AppLaunchTarget?>(null)

    init {
        // letta-mobile-erx7m: touch-stall probes, debug builds only.
        TouchDispatchDiagnostics.enableForDebugBuild()
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        ProfileCaptureKeyguardHelper.allowProfileCaptureLaunch(this)
        importAutomationPayloadFromLaunchIntent()
        launchTarget.value = PairLinkLaunchTarget.consume(intent)
        enableEdgeToEdge()
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
            val snackbarDispatcher = remember { SnackbarDispatcher() }
            val overlays = rememberOverlayHost()
            val haptic = LocalHapticFeedback.current
            val view = LocalView.current
            val appThemeState = settingsRepository.getTheme().collectAsStateWithLifecycle(initialValue = AppTheme.SYSTEM)
            val themePresetState = settingsRepository.getThemePreset().collectAsStateWithLifecycle(initialValue = ThemePreset.DEFAULT)
            val dynamicColorState = settingsRepository.getDynamicColor().collectAsStateWithLifecycle(initialValue = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

            LaunchedEffect(Unit) {
                // letta-mobile-ckmub.3.15: app screens' messages join the activity's one overlay queue.
                snackbarDispatcher.messages.collect { message ->
                    HapticEffects.reject(haptic, view)
                    overlays.show(message.toOverlay())
                }
            }

            LastCrashSnackbarEffect(overlays)
            ChatPushServiceStartEffect()

            LettaTheme(
                appTheme = appThemeState.value,
                themePreset = themePresetState.value,
                dynamicColor = dynamicColorState.value,
            ) {
                CompositionLocalProvider(
                    LocalSnackbarDispatcher provides snackbarDispatcher,
                    LocalWindowSizeClass provides windowSizeClass,
                    LocalAttentionPrefs provides deps.attentionPrefs,
                ) {
                    ProvideMascotShell(
                        agents = deps.agentRepository.agents,
                        settings = deps.secureSettingsStore,
                        runs = deps.conversationRunRegistry,
                    ) {
                        Surface(modifier = debugRootPointerObserver(), color = MaterialTheme.colorScheme.background) {
                            OverlayHostRoot(overlays) {
                                val navController = rememberNavController()
                                AdaptiveScaffold(navController = navController) {
                                    AppNavGraph(
                                        navController = navController,
                                        notificationTarget = launchTarget.value,
                                        onNotificationTargetConsumed = { launchTarget.value = null },
                                    )
                                }
                            }
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

    /**
     * Surface the previous crash briefly without blocking the composer.
     * Copy/dismiss leaves the diagnostic record available on disk.
     */
    @Composable
    private fun LastCrashSnackbarEffect(overlays: OverlayHost) {
        val haptic = LocalHapticFeedback.current
        val view = LocalView.current
        val lastCrashState = crashReporter.lastCrash.collectAsStateWithLifecycle()
        LaunchedEffect(lastCrashState.value) {
            val crash = lastCrashState.value ?: return@LaunchedEffect
            HapticEffects.reject(haptic, view)
            val label = if (crash.sentryEventId != null) "Copy id" else "Details"
            val summary = "App crashed last run (${crash.type.substringAfterLast('.')}). Tap to copy."
            val copy = OverlayAction(label) {
                val payload = crash.sentryEventId
                    ?: "${crash.type}: ${crash.message}\n${crash.stackHead}"
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("letta-mobile crash", payload))
                Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            overlays.show(Overlay.Snackbar(summary, presentation = OverlayPresentation.Long, response = OverlayResponse(copy)))
            crashReporter.dismiss()
        }
    }

    /**
     * letta-mobile-mge5: start the foreground service that keeps resume-stream subscribers alive
     * even when the app is backgrounded or the screen is off, asking for POST_NOTIFICATIONS first
     * where Android requires it.
     */
    @Composable
    private fun ChatPushServiceStartEffect() {
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { granted ->
                if (granted) {
                    ChatPushService.start(this@MainActivity)
                }
            },
        )
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@LaunchedEffect
            }
            ChatPushService.start(this@MainActivity)
        }
    }

    /** letta-mobile-erx7m: logs DOWN/UP/CANCEL reaching the window and whether it was handled. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.dispatchTouchEvent(ev)
        TouchDispatchDiagnostics.onDispatched(ev, handled)
        return handled
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importAutomationPayloadFromLaunchIntent()
        launchTarget.value = PairLinkLaunchTarget.consume(intent)
    }

    private fun importAutomationPayloadFromLaunchIntent() {
        val encodedPayload = intent.getStringExtra(AutomationAuthBootstrap.EXTRA_PAYLOAD_BASE64)
            ?.trim()
            .orEmpty()
        if (encodedPayload.isBlank()) {
            return
        }
        getSharedPreferences(AutomationAuthBootstrap.PREFS_NAME, MODE_PRIVATE)
            .edit(commit = true) {
                putString(AutomationAuthBootstrap.KEY_PAYLOAD_BASE64, encodedPayload)
            }
        AutomationAuthBootstrap.importPendingConfig(this, settingsRepository)
        intent.removeExtra(AutomationAuthBootstrap.EXTRA_PAYLOAD_BASE64)
    }
}
