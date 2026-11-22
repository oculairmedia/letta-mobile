package com.letta.mobile.ui.components.field

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.theme.tokens.LettaFieldTokens
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion

/** Where a text input is: at rest, under the pointer, or holding focus (its own or a control's inside it). */
internal enum class LettaFieldState { Rest, Hover, Focus }

/** The look a field's border shows (tests read it; pixels cannot be asserted). */
internal val LettaFieldStateKey = SemanticsPropertyKey<LettaFieldState>("LettaFieldState")
internal var SemanticsPropertyReceiver.lettaFieldState by LettaFieldStateKey

/** A field container's pointer and focus, for [state]; remember one per container ([rememberLettaField]). */
@Stable
internal class LettaFieldInteraction internal constructor() {
    internal val hover: MutableInteractionSource = MutableInteractionSource()
    internal var focused: Boolean by mutableStateOf(false)

    /** Focus wins over hover: a focused field keeps its look while the pointer passes. */
    @Composable
    internal fun state(): LettaFieldState {
        val hovered by hover.collectIsHoveredAsState()
        return when {
            focused -> LettaFieldState.Focus
            hovered -> LettaFieldState.Hover
            else -> LettaFieldState.Rest
        }
    }
}

@Composable
internal fun rememberLettaField(): LettaFieldInteraction = remember { LettaFieldInteraction() }

/**
 * Tracks [field] on a text input's container: the pointer over it, and focus anywhere inside it (the
 * field itself or one of its controls, like CSS's focus-within). Exposes [state] to semantics.
 */
internal fun Modifier.lettaField(field: LettaFieldInteraction, state: LettaFieldState): Modifier =
    this
        .hoverable(field.hover)
        .onFocusChanged { field.focused = it.hasFocus }
        .semantics { lettaFieldState = state }

/**
 * A text input's border for [state]: `outlineVariant` as a hairline at
 * rest, `outline` under the pointer, `primary` while focused, always [LettaFieldTokens.border] wide so
 * nothing inside moves. The colour eases between looks, at once under reduced motion.
 */
@Composable
internal fun lettaFieldBorder(state: LettaFieldState): BorderStroke {
    val scheme = MaterialTheme.colorScheme
    val target = when (state) {
        LettaFieldState.Rest -> scheme.outlineVariant.copy(alpha = LettaFieldTokens.REST_ALPHA)
        LettaFieldState.Hover -> scheme.outline
        LettaFieldState.Focus -> scheme.primary
    }
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = if (LocalLettaReducedMotion.current) snap() else tween(LettaFieldTokens.BORDER_MILLIS),
        label = "lettaFieldBorder",
    )
    return BorderStroke(LettaFieldTokens.border, color)
}
