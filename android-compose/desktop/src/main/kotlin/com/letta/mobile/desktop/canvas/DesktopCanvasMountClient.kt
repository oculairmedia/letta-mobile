package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.mount.client.CanvasMountClient

/**
 * The desktop's view of the host's canvas mounts, shared by its windows and the canvas_* tools its
 * App Server answers ([DesktopCanvasHostSync.mounts]). The host's admin RPC client is not wired yet,
 * so nothing is mounted and every conversation shows its own board.
 */
internal object DesktopCanvasMountClient : CanvasMountClient by CanvasMountClient.Unsupported
