@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.letta.mobile.desktop.avatar.pet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/** Visual state of the pet's presence dot in the reply popup header. */
private fun phaseDotColor(phase: PetChatPhase): Color = when (phase) {
    PetChatPhase.THINKING -> Color(0xFFE0A33E) // amber — working (PRD presence)
    PetChatPhase.REPLYING -> Color(0xFF34C759) // green — speaking
    PetChatPhase.ERROR -> Color(0xFFE5484D) // red — error
    PetChatPhase.CONNECTING -> Color(0xFF8A8A93) // grey — not live yet
    PetChatPhase.IDLE -> Color(0xFF34C759) // green — ready
}

/**
 * The focusable reply popup (PRD §5 B1). A SEPARATE small Compose window from
 * the pet: the pet window is non-focusable by contract (AWT
 * focusableWindowState=false + WS_EX_NOACTIVATE), so a text field inside it can
 * never receive keyboard input. This window exists solely to type into — it is
 * ALLOWED to take focus (focusable=true), undecorated, dark rounded card.
 *
 * Positioned just below/beside the pet window ([anchorX]/[anchorY] are the pet
 * window's on-screen origin), clamped to the screen by [rememberWindowState]'s
 * absolute [WindowPosition]. Enter sends; Escape (or the ✕) closes.
 */
@Composable
fun PetReplyWindow(
    chat: PetChatUiState,
    anchorX: Int,
    anchorY: Int,
    anchorHeight: Int,
    onSend: (String) -> Boolean,
    onClose: () -> Unit,
) {
    // Default position: BELOW the pet (under her feet). When the pet is parked
    // at the bottom edge there is no room below, so fall back to beside her,
    // bottom-aligned (left first, then right), always clamped on-screen. The
    // header is a drag handle, so the user can reposition freely after open;
    // the initial position only applies when the popup (re)opens.
    val popupWidth = 360
    val popupHeight = 128
    val gap = 8
    val screen = remember {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }
    val below = anchorY + anchorHeight + gap
    val x: Int
    val y: Int
    if (below + popupHeight <= screen.y + screen.height) {
        x = anchorX.coerceIn(0, (screen.width - popupWidth).coerceAtLeast(0))
        y = below
    } else {
        val left = anchorX - popupWidth - gap
        x = if (left >= 0) left else (anchorX + 380 + gap).coerceAtMost(screen.width - popupWidth)
        y = (anchorY + anchorHeight - popupHeight).coerceAtLeast(0)
    }

    val windowState = rememberWindowState(
        width = popupWidth.dp,
        height = popupHeight.dp,
        position = WindowPosition(x.dp, y.dp),
    )

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Reply to ${chat.agent?.displayName ?: "pet"}",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = true, // ALLOWED here — this window exists to take keyboard focus.
        alwaysOnTop = true,
    ) {
        PetReplyCard(chat = chat, onSend = onSend, onClose = onClose)
    }
}

@Composable
private fun androidx.compose.ui.window.WindowScope.PetReplyCard(
    chat: PetChatUiState,
    onSend: (String) -> Boolean,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Grab focus the moment the popup opens so the user types immediately.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun submit() {
        val value = text.trim()
        // Only clear the field if the send was accepted; a rejected send (not
        // live yet / had to spin up a conversation first) keeps the draft so the
        // user can retry without retyping.
        if (value.isNotEmpty() && onSend(value)) {
            text = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF21C1C22), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp))
            // Escape anywhere in the card closes it (PRD: Escape/✕ closes).
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose(); true
                } else {
                    false
                }
            }
            .padding(14.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header: presence dot + agent name + close. Also the drag handle —
            // the card is repositionable by grabbing anywhere along this row.
            WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(phaseDotColor(chat.phase), CircleShape),
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = chat.agent?.displayName ?: "Pet",
                        style = TextStyle(color = Color(0xFFE8E8EC), fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                    )
                    BasicText(
                        text = chat.statusLine,
                        style = TextStyle(color = Color(0xFF8A8A93), fontSize = 11.sp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .clickable(onClick = onClose)
                            .padding(horizontal = 4.dp),
                    ) {
                        BasicText(text = "✕", style = TextStyle(color = Color(0xFF8A8A93), fontSize = 13.sp))
                    }
                }
            }

            Spacer(Modifier.padding(top = 10.dp))

            // Input row: single-line field (Enter sends) + send button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0x1AFFFFFF), RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    if (text.isEmpty()) {
                        BasicText(
                            text = "Message…",
                            style = TextStyle(color = Color(0xFF6C6C74), fontSize = 13.sp),
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { new -> text = new.replace("\n", "") },
                        singleLine = true,
                        textStyle = TextStyle(color = Color(0xFFF3F3F6), fontSize = 13.sp),
                        cursorBrush = SolidColor(Color(0xFF34C759)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            // Enter sends even when the IME action isn't wired
                            // on a hardware keyboard (desktop).
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                                    submit(); true
                                } else {
                                    false
                                }
                            },
                    )
                }
                Spacer(Modifier.width(8.dp))
                val enabled = chat.canSend && text.isNotBlank()
                Box(
                    modifier = Modifier
                        .background(
                            if (enabled) Color(0xFF2C7A4B) else Color(0x332C7A4B),
                            RoundedCornerShape(9.dp),
                        )
                        .let { m -> if (enabled) m.clickable { submit() } else m }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    BasicText(
                        text = "Send",
                        style = TextStyle(
                            color = if (enabled) Color(0xFFFFFFFF) else Color(0xFF8A8A93),
                            fontSize = 13.sp,
                        ),
                    )
                }
            }
        }
    }
}
