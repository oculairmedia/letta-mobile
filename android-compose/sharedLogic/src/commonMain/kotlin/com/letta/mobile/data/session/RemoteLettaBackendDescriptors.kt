package com.letta.mobile.data.session

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.BackendCapabilities
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.BackendKind
import com.letta.mobile.runtime.RuntimeId

const val DEFAULT_REMOTE_LETTA_URL = "https://api.letta.com"

/** Android remote descriptor id prefix (`remote-letta:<configId>`). */
const val ANDROID_REMOTE_LETTA_ID_PREFIX = "remote-letta"

/** Desktop remote descriptor id prefix (`desktop-remote-letta:<configId>`). */
const val DESKTOP_REMOTE_LETTA_ID_PREFIX = "desktop-remote-letta"

/**
 * Shared remote-Letta [BackendDescriptor] for hosts that are not running a
 * local embedded runtime. [idPrefix] keeps Android and Desktop ids distinct
 * while capabilities stay identical.
 */
fun remoteLettaBackendDescriptor(
    config: LettaConfig?,
    idPrefix: String,
    defaultLabel: String = DEFAULT_REMOTE_LETTA_URL,
): BackendDescriptor {
    val backendKey = config?.id?.takeIf { it.isNotBlank() } ?: "default"
    val label = config?.serverUrl?.trim()?.takeIf { it.isNotBlank() } ?: defaultLabel
    return BackendDescriptor(
        backendId = BackendId("$idPrefix:$backendKey"),
        runtimeId = RuntimeId("$idPrefix:$backendKey"),
        kind = BackendKind.RemoteLetta,
        label = label,
        capabilities = BackendCapabilities(
            supportsStreaming = true,
            supportsMemFs = true,
            supportsToolEvents = true,
            supportsToolExecution = true,
            supportsApprovals = true,
            supportsAgentFileImport = true,
            supportsAgentFileExport = true,
        ),
    )
}
