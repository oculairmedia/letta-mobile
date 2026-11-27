package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.node.embedded.BundledRuntimeNode
import java.io.File

/** The desktop's own node over the bundled letta-code runtime; its lines go to the runtime's log, tagged. */
internal object DesktopBundledRuntimeNode : BundledRuntimeNode(
    ownedProcess = DesktopLocalRuntimeHost.ownedProcess,
    backendDirectory = DesktopLocalRuntimeHost::backendDirectory,
    appServer = DesktopLocalRuntimeHost.appServer::acquire,
    logFile = File(System.getProperty("user.home"), ".letta-mobile/logs/local-runtime.log"),
)
