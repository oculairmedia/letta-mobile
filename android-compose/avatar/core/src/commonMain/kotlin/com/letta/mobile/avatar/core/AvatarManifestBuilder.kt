package com.letta.mobile.avatar.core

/**
 * Build the normalized manifest from a byte-level [AvatarDetection] plus the
 * import-time facts detection can't know (identity, license, provenance,
 * hash, stats from the external asset report). This is the single
 * normalization path — the asset pipeline calls this; nothing else composes
 * manifests by hand.
 */
fun AvatarDetection.toManifest(
    id: String,
    displayName: String,
    license: AvatarLicense = AvatarLicense(),
    source: AvatarAssetSource = AvatarAssetSource(),
    sha256: String? = null,
    stats: AvatarAssetStats = AvatarAssetStats(),
    accessories: List<AvatarAccessoryInfo> = emptyList(),
    extraWarnings: List<String> = emptyList(),
): AvatarManifest {
    require(id.isNotBlank()) { "Manifest id must not be blank" }
    return AvatarManifest(
        id = id,
        displayName = displayName,
        format = format,
        capabilities = capabilities.copy(
            supportsAccessories = accessories.isNotEmpty(),
        ),
        humanoidBones = humanoidBones,
        expressions = expressions,
        visemes = visemes,
        animations = animations,
        accessories = accessories,
        stats = stats,
        license = license,
        source = source,
        sha256 = sha256,
        warnings = warnings + extraWarnings,
    )
}

/** The catalog entry for a packed asset described by [this] manifest. */
fun AvatarManifest.toModel(
    uri: String,
    manifestUri: String? = null,
    thumbnailUri: String? = null,
): AvatarModel =
    AvatarModel(
        id = id,
        displayName = displayName,
        uri = uri,
        format = format,
        manifestUri = manifestUri,
        thumbnailUri = thumbnailUri,
        license = license,
        source = source,
        sha256 = sha256,
    )
