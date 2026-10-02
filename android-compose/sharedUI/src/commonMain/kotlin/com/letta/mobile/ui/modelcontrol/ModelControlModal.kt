package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.theme.LettaDimens

/**
 * How a model-control surface is presented: a compact centred card on
 * desktop, a bottom sheet on a phone. Each host picks the one that fits its
 * platform; the content inside is the same composable.
 */
enum class ModelControlPresentation {
    Dialog,
    Sheet,
}

/** Scrim alpha behind a centred desktop modal. */
private const val SCRIM_ALPHA = 0.45f

/**
 * The container the model picker and the Models sheet open in. [Dialog] is an
 * in-window card over a scrim (a click outside or Escape dismisses it, like the
 * desktop palette); [Sheet] is a Material bottom sheet.
 */
@Composable
fun ModelControlModal(
    presentation: ModelControlPresentation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (presentation) {
        ModelControlPresentation.Dialog -> CenteredModal(onDismiss, modifier, content)
        ModelControlPresentation.Sheet -> SheetModal(onDismiss, modifier, content)
    }
}

@Composable
private fun CenteredModal(onDismiss: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = modifier
                .padding(LettaDimens.Space.lg)
                .widthIn(max = LettaDimens.Pane.modalWidth)
                .fillMaxWidth()
                .heightIn(max = LettaDimens.Pane.modalMaxHeight)
                .semantics { dialog() }
                // Absorb clicks so they don't fall through to the dismissing scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            shape = RoundedCornerShape(LettaDimens.Radius.lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = LettaDimens.Space.sm,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SheetModal(onDismiss: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.testTag(ModelControlModalTags.SHEET),
    ) {
        Column(modifier = Modifier.navigationBarsPadding(), content = content)
    }
}

object ModelControlModalTags {
    const val SHEET = "model_control_sheet"
}
