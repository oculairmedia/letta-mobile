package com.letta.mobile.avatar.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** On-disk formats the avatar stack understands. The mascot is a Rive file; nothing else ships. */
@Serializable
enum class AvatarFormat {
    /** A Rive `.riv`: artboard + state machine + view model, driven through [AvatarRuntime]. */
    @SerialName("rive")
    RIVE,
}

/**
 * The asset the app hands to an [AvatarRuntime]. Everything a renderer needs to locate and
 * trust it - never renderer-specific state.
 */
@Serializable
data class AvatarModel(
    val id: String,
    val displayName: String,
    /** Location of the packaged runtime asset (`res://raw/mascot.riv`, a classpath resource, ...). */
    val uri: String,
    val format: AvatarFormat = AvatarFormat.RIVE,
    val thumbnailUri: String? = null,
    /** Hash of the packaged asset bytes - cache invalidation + audit trail. */
    val sha256: String? = null,
)

/**
 * What an asset can actually do, re-checked by renderers on load. The app keys UI affordances
 * off these flags instead of poking at format internals.
 */
@Serializable
data class AvatarCapabilities(
    val supportsExpressions: Boolean = false,
    val supportsLookAt: Boolean = false,
    val supportsEmbeddedAnimations: Boolean = false,
    val supportsAccessories: Boolean = false,
)
