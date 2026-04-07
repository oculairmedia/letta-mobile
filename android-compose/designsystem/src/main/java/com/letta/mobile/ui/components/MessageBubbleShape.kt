package com.letta.mobile.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.common.GroupPosition

private val SMALL_RADIUS_RATIO = 0.25f

class MessageBubbleShape(
    private val radius: Dp,
    private val isFromUser: Boolean = false,
    private val groupPosition: GroupPosition = GroupPosition.None,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }
        val small = r * SMALL_RADIUS_RATIO

        val topStart: Float
        val topEnd: Float
        val bottomStart: Float
        val bottomEnd: Float

        if (isFromUser) {
            topStart = when (groupPosition) {
                GroupPosition.First, GroupPosition.None -> r
                else -> r
            }
            topEnd = when (groupPosition) {
                GroupPosition.First, GroupPosition.None -> small
                else -> small
            }
            bottomStart = r
            bottomEnd = when (groupPosition) {
                GroupPosition.Last, GroupPosition.None -> r
                else -> small
            }
        } else {
            topStart = when (groupPosition) {
                GroupPosition.First, GroupPosition.None -> small
                else -> small
            }
            topEnd = when (groupPosition) {
                GroupPosition.First, GroupPosition.None -> r
                else -> r
            }
            bottomStart = when (groupPosition) {
                GroupPosition.Last, GroupPosition.None -> r
                else -> small
            }
            bottomEnd = r
        }

        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    topLeftCornerRadius = CornerRadius(topStart),
                    topRightCornerRadius = CornerRadius(topEnd),
                    bottomLeftCornerRadius = CornerRadius(bottomStart),
                    bottomRightCornerRadius = CornerRadius(bottomEnd),
                )
            )
        }
        return Outline.Generic(path)
    }
}
