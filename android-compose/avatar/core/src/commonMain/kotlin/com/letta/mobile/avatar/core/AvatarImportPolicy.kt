package com.letta.mobile.avatar.core

/** Where an imported asset is allowed to live. */
enum class AvatarImportVisibility {
    /** Usable only on this device by this user; never redistributed. */
    PRIVATE_LOCAL,

    /** Enters the shared/redistributable catalog. */
    SHARED_CATALOG,
}

/** Outcome of a license check at import time. */
sealed interface AvatarImportDecision {
    /** Import as requested. */
    data object Allowed : AvatarImportDecision

    /**
     * Import is permitted but MUST be downgraded to
     * [AvatarImportVisibility.PRIVATE_LOCAL] regardless of what was asked.
     */
    data class AllowedLocalOnly(val reason: String) : AvatarImportDecision

    /** Import must not proceed. */
    data class Rejected(val reason: String) : AvatarImportDecision
}

/**
 * License gate for the asset pipeline: assets with unknown or denied
 * redistribution rights never enter the shared catalog, but personal use of
 * an asset the user obtained themselves stays possible as local/private.
 * Unknown (`null`) flags are treated as most-restrictive.
 */
object AvatarImportPolicy {
    fun evaluate(
        license: AvatarLicense,
        intendedVisibility: AvatarImportVisibility,
    ): AvatarImportDecision = when (license.allowRedistribution) {
        true -> AvatarImportDecision.Allowed
        false -> when (intendedVisibility) {
            AvatarImportVisibility.PRIVATE_LOCAL -> AvatarImportDecision.Allowed
            AvatarImportVisibility.SHARED_CATALOG -> AvatarImportDecision.Rejected(
                "License forbids redistribution" + licenseSuffix(license),
            )
        }
        null -> when (intendedVisibility) {
            AvatarImportVisibility.PRIVATE_LOCAL -> AvatarImportDecision.AllowedLocalOnly(
                "Redistribution rights unknown; keeping the asset private" +
                    licenseSuffix(license),
            )
            AvatarImportVisibility.SHARED_CATALOG -> AvatarImportDecision.Rejected(
                "Redistribution rights unknown; re-import as local/private or " +
                    "capture the license" + licenseSuffix(license),
            )
        }
    }

    private fun licenseSuffix(license: AvatarLicense): String =
        license.licenseName?.let { " ($it)" }.orEmpty()
}
