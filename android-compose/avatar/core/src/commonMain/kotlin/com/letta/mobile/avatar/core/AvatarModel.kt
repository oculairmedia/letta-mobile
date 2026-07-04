package com.letta.mobile.avatar.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical on-disk formats the avatar stack understands. glTF 2.0 / GLB is
 * the runtime container; VRM is the humanoid profile layered on top of it.
 * VRM 0.x assets are importable but are normalized into the same manifest and
 * capability model as VRM 1.0 at import time — renderers never branch on 0.x.
 */
@Serializable
enum class AvatarFormat {
    /** VRM 1.0 humanoid avatar (glTF + `VRMC_vrm` extension). */
    @SerialName("vrm1")
    VRM_1,

    /** Legacy VRM 0.x humanoid avatar (glTF + `VRM` extension). */
    @SerialName("vrm0")
    VRM_0,

    /** Binary glTF container — props, rooms, mascots, non-humanoids. */
    @SerialName("glb")
    GLB,

    /** JSON glTF (external buffers) — accepted at import, packed to GLB. */
    @SerialName("gltf")
    GLTF,
    ;

    val isHumanoidProfile: Boolean
        get() = this == VRM_1 || this == VRM_0
}

/**
 * A catalog entry the app hands to an [AvatarRuntime]. Everything a renderer
 * needs to locate and trust an asset — never renderer-specific state.
 */
@Serializable
data class AvatarModel(
    val id: String,
    val displayName: String,
    /** Location of the packaged runtime asset (a `.glb`/`.vrm` file). */
    val uri: String,
    val format: AvatarFormat,
    /** Location of the normalized `avatar.manifest.json` generated at import. */
    val manifestUri: String? = null,
    val thumbnailUri: String? = null,
    val license: AvatarLicense = AvatarLicense(),
    val source: AvatarAssetSource = AvatarAssetSource(),
    /** Hash of the packaged asset bytes — cache invalidation + audit trail. */
    val sha256: String? = null,
)

/**
 * A user-provided standard animation to load alongside an [AvatarModel] and
 * retarget onto it at runtime — VRMA, Mixamo FBX, or a Mixamo-compatible GLB
 * (e.g. the Ready Player Me animation library) dropped into the animation
 * folder, keyed by a stable [id]. Renderer-independent: the web renderer fetches
 * [uri], parses by [format], and registers the retargeted clip under [id];
 * headless runtimes just record it. Humanoid-only in practice — non-humanoid
 * GLB has no rig to retarget onto, so these are ignored there.
 */
@Serializable
data class AvatarAnimationSource(
    /** Clip id it registers under; also the id used to play it. */
    val id: String,
    /** Location of the animation file (renderer-resolvable). */
    val uri: String,
    val format: AvatarAnimationFormat,
)

/** Container format of an [AvatarAnimationSource]. */
@Serializable
enum class AvatarAnimationFormat {
    /** VRM Animation (`.vrma`) — glTF + `VRMC_vrm_animation`. */
    @SerialName("vrma")
    VRMA,

    /** Mixamo humanoid animation (`.fbx`). */
    @SerialName("fbx")
    FBX,

    /**
     * Mixamo-compatible humanoid animation packaged as glTF binary (`.glb`) —
     * an `Armature` whose bones use the Mixamo naming scheme with the
     * `mixamorig` prefix stripped ("Hips", "Spine", "LeftForeArm", ...), as
     * shipped by the Ready Player Me open animation library. The renderer
     * retargets it through the same math as [FBX]; only the container and the
     * (prefix-less) bone names differ. NOTE: avatar *models* are also `.glb`,
     * so extension alone does not distinguish the two — an [AvatarAnimationSource]
     * is always an animation because the drop-in animation directory is scanned
     * exclusively for animations (see the desktop drop-in scanner).
     */
    @SerialName("glb")
    GLB,
    ;

    /** Wire string carried on the renderer protocol. */
    val wireName: String
        get() = when (this) {
            VRMA -> "vrma"
            FBX -> "fbx"
            GLB -> "glb"
        }
}

/**
 * License metadata captured at import time. `null` on any permission flag
 * means UNKNOWN, which [AvatarImportPolicy] treats as most-restrictive — an
 * asset with unknown redistribution rights never enters the shared catalog.
 */
@Serializable
data class AvatarLicense(
    val sourceUrl: String? = null,
    val creator: String? = null,
    val licenseName: String? = null,
    val licenseUrl: String? = null,
    val allowCommercialUse: Boolean? = null,
    val allowRedistribution: Boolean? = null,
    val allowModification: Boolean? = null,
    val attributionRequired: Boolean? = null,
    val notes: String? = null,
)

/** Where an asset came from — provenance for the catalog and audits. */
@Serializable
data class AvatarAssetSource(
    /** Origin URL (VRoid Hub page, Sketchfab model page, local path, …). */
    val url: String? = null,
    /** Free-form origin kind, e.g. "vroid-studio", "sketchfab", "local". */
    val kind: String? = null,
    /** ISO-8601 timestamp of when the asset was retrieved/imported. */
    val retrievedAt: String? = null,
)

/**
 * What an asset can actually do, derived at import time (and re-checked by
 * renderers on load). The app keys UI affordances off these flags instead of
 * poking at format internals.
 */
@Serializable
data class AvatarCapabilities(
    val supportsHumanoid: Boolean = false,
    val supportsExpressions: Boolean = false,
    val supportsVisemes: Boolean = false,
    val supportsLookAt: Boolean = false,
    val supportsSpringBones: Boolean = false,
    val supportsEmbeddedAnimations: Boolean = false,
    val supportsAccessories: Boolean = false,
)
