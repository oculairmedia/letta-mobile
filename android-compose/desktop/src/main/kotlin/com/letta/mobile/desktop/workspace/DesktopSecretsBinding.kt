package com.letta.mobile.desktop.workspace

import com.letta.mobile.data.secrets.AgentSecretsSource
import com.letta.mobile.data.secrets.AppServerAgentSecretsSource

/** letta-mobile-bzvro.25: the secrets vault's source on the desktop's direct App Server session. */
internal fun DesktopWorkspaceSources.secrets(): AgentSecretsSource =
    AppServerAgentSecretsSource(client = ::client, requestId = ::requestId)
