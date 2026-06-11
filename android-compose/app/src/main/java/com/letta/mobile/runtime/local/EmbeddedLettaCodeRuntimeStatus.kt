package com.letta.mobile.runtime.local

import com.letta.mobile.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

data class EmbeddedLettaCodeRuntimeStatus(
    val nativeEnabled: Boolean,
    val assetsEnabled: Boolean,
    val version: String,
    val integrity: String,
) {
    val runnable: Boolean
        get() = false
}

interface EmbeddedLettaCodeRuntimeStatusProvider {
    val status: EmbeddedLettaCodeRuntimeStatus
}

@Singleton
class BuildConfigEmbeddedLettaCodeRuntimeStatusProvider @Inject constructor() :
    EmbeddedLettaCodeRuntimeStatusProvider {
    override val status: EmbeddedLettaCodeRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
        nativeEnabled = BuildConfig.EMBEDDED_LETTACODE_NATIVE_ENABLED,
        assetsEnabled = BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED,
        version = BuildConfig.EMBEDDED_LETTACODE_VERSION,
        integrity = BuildConfig.EMBEDDED_LETTACODE_INTEGRITY,
    )
}
