package com.letta.mobile.runtime.local

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
class DisabledEmbeddedLettaCodeRuntimeStatusProvider @Inject constructor() :
    EmbeddedLettaCodeRuntimeStatusProvider {
    override val status: EmbeddedLettaCodeRuntimeStatus = EmbeddedLettaCodeRuntimeStatus(
        nativeEnabled = false,
        assetsEnabled = false,
        version = "disabled",
        integrity = "",
    )
}
