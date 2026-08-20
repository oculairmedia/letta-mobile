package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig

/**
 * Stable key for "did the backend connection change?" session rebuilds.
 *
 * Includes local-model fields so selecting an embedded model on the same
 * config id still triggers a rebuild (letta-mobile-mlyhq).
 */
data class BackendConnectionKey(
    val mode: LettaConfig.Mode,
    val serverUrl: String,
    val accessToken: String?,
    val localModelPath: String?,
    val localModelHandle: String?,
    val localModelRuntime: String?,
    val localModelAccelerator: String?,
    val localModelMaxTokens: Int?,
    val localProviderBaseUrl: String?,
    val localProviderApiKey: String?,
    val localProviderModel: String?,
)

fun LettaConfig.backendConnectionKey(): BackendConnectionKey = BackendConnectionKey(
    mode = mode,
    serverUrl = serverUrl,
    accessToken = accessToken,
    localModelPath = localModelPath,
    localModelHandle = localModelHandle,
    localModelRuntime = localModelRuntime,
    localModelAccelerator = localModelAccelerator,
    localModelMaxTokens = localModelMaxTokens,
    localProviderBaseUrl = localProviderBaseUrl,
    localProviderApiKey = localProviderApiKey,
    localProviderModel = localProviderModel,
)
