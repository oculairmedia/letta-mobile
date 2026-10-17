package com.letta.mobile.ui.mascot

import androidx.compose.ui.geometry.Rect
import com.letta.mobile.avatar.core.GazePoint
import com.letta.mobile.avatar.core.GazeReach
import com.letta.mobile.avatar.core.GazeTargetRects
import com.letta.mobile.avatar.core.GazeWindow
import com.letta.mobile.data.model.AgentId

/**
 * What the mascot drawn at [tile] (window px) for [agentId] can look at, from everything the
 * registry holds: the pointer (desktop's mouse, Android's last touch), the composer and timeline,
 * every attention point, and the other agents' mascots. Reads snapshot state only, so [MascotLive]
 * calls it inside a snapshotFlow and the tile never recomposes for a moving target.
 */
internal fun MascotIdentityRegistry.gazeWindow(agentId: AgentId, tile: Rect, reach: GazeReach): GazeWindow =
    GazeWindow(
        mascot = tile.toGazeRect(),
        reach = reach,
        pointerPx = cursor.value?.let { GazePoint(it.x, it.y) },
        rects = GazeTargetRects(
            input = inputBounds.value,
            timeline = timelineBounds.value,
            attention = attention.values.toList(),
        ),
        peersPx = mascotBounds.values
            .filter { it.agentId != agentId.value && !it.bounds.isEmpty }
            .map { GazePoint(it.bounds.centerX, it.bounds.centerY) },
    )
