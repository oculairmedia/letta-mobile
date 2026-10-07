package com.letta.mobile.data.repository.api

/**
 * A preview switch the user flips in Settings: its persisted preference key and its value until
 * flipped. Read and written through [ISettingsRepository.getFeatureFlag] / [ISettingsRepository.setFeatureFlag],
 * so a new preview is one entry here rather than another getter/setter pair on every repository.
 */
enum class FeatureFlag(val preferenceKey: String, val defaultEnabled: Boolean) {
    /**
     * letta-mobile-c3np7.5.5: the phone's hamburger opens the shared navigation drawer (sharedUI
     * ShellAgentRail + ShellAgentPanel, the desktop's rail and agent panel) instead of the legacy
     * chat drawer.
     */
    SharedNavDrawer(preferenceKey = "shared_nav_drawer", defaultEnabled = false),
}
