package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.mount.client.CanvasMountClient
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasResolver
import com.letta.mobile.data.canvas.mount.client.DeviceFallbackCanvasMountClient

/**
 * The desktop's view of the host's canvas mounts, shared by its windows and the canvas_* tools its
 * App Server answers ([mounts]). The host's admin RPC client is not wired yet, so the host offers
 * no mounts and a pick is mounted on this device only (`DeviceFallbackCanvasMountClient`).
 */
internal object DesktopCanvasMountClient : CanvasMountClient by CanvasMountClient.Unsupported {
    /** Which board each conversation shows, for the windows and the tools: the host's mounts, else this device's. */
    val mounts = ConversationCanvasResolver(DeviceFallbackCanvasMountClient(this))
}
