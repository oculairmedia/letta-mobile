package com.letta.mobile.ui.providers.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Builds the provider logos ported from lobe-icons (MIT, see sharedUI/THIRD_PARTY_LICENSES): every
 * monochrome lobe icon is a 24×24 viewport of even-odd filled paths, some at a lower opacity. The fill
 * is black so an `Icon` tint recolours it while each path keeps its alpha.
 */
internal class LobePath(val pathData: String, val alpha: Float = 1f)

private const val LOBE_VIEWPORT = 24f

internal fun lobeIcon(name: String, paths: List<LobePath>): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = LOBE_VIEWPORT.dp,
        defaultHeight = LOBE_VIEWPORT.dp,
        viewportWidth = LOBE_VIEWPORT,
        viewportHeight = LOBE_VIEWPORT,
    ).apply {
        paths.forEach { path ->
            addPath(
                pathData = addPathNodes(path.pathData),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
                fillAlpha = path.alpha,
            )
        }
    }.build()
