package com.letta.mobile.data.repository.api

/**
 * A preview switch the user flips in Settings: its persisted preference key and its value until
 * flipped. Read and written through [ISettingsRepository.getFeatureFlag] / [ISettingsRepository.setFeatureFlag],
 * so a new preview is one entry here rather than another getter/setter pair on every repository.
 *
 * No preview is open right now: the shared navigation drawer (letta-mobile-c3np7.5.5) became the
 * only drawer in hard cut S9 (letta-mobile-ckmub.2.2).
 */
enum class FeatureFlag(val preferenceKey: String, val defaultEnabled: Boolean) {
    ;
}
