package com.letta.mobile

import com.letta.mobile.crash.CrashReporter
import com.letta.mobile.data.repository.api.ISettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructor-injected Activity collaborators for [MainActivity].
 *
 * `@AndroidEntryPoint` activities cannot use constructor injection on the
 * Activity itself (letta-mobile-l2ew9.2).
 */
@Singleton
class MainActivityDependencies @Inject constructor(
    val settingsRepository: ISettingsRepository,
    val crashReporter: CrashReporter,
)
