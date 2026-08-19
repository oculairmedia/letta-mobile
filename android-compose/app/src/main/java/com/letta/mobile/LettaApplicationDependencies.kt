package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.startup.AppStartupCoordinator
import com.letta.mobile.util.EncryptedPrefsHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructor-injected Application collaborators.
 *
 * `@HiltAndroidApp` cannot take a constructor; the Application field-injects
 * this facade so production logic stays constructor-testable (letta-mobile-l2ew9.2).
 *
 * [encryptedPrefsHelper] is retained as a constructor dep so Hilt eagerly
 * creates the syf4 static-bridge singleton during Application injection.
 */
@Singleton
class LettaApplicationDependencies @Inject constructor(
    val crashReporter: CrashReporter,
    val appStartupCoordinator: AppStartupCoordinator,
    @Suppress("unused") val encryptedPrefsHelper: EncryptedPrefsHelper,
)
