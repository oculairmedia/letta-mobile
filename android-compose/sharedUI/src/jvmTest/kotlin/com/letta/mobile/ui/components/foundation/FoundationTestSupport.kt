@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.SharedMaterialTheme

/** The phone and desktop widths every width-dependent foundation component is checked at. */
internal val FOUNDATION_WIDTHS: List<Dp> = listOf(360.dp, 1200.dp)

/** A test window wider and taller than the widest checked width, so a 1200 dp box is not clipped to the default window. */
internal const val FOUNDATION_WINDOW_WIDTH = 1400
internal const val FOUNDATION_WINDOW_HEIGHT = 900

/** Sets [content] in the shared theme inside a box [width] wide, as a page would host it. */
internal fun ComposeUiTest.setFoundationContent(width: Dp = FOUNDATION_WIDTHS.first(), content: @Composable () -> Unit) {
    setContent {
        SharedMaterialTheme(darkTheme = true) {
            Surface { Box(Modifier.width(width)) { content() } }
        }
    }
}

/** Matches a node whose semantics role is [role]. */
internal fun hasRole(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
