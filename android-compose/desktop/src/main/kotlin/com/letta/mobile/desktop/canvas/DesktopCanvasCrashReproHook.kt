package com.letta.mobile.desktop.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.system.exitProcess

/**
 * Test hook, off unless -Dletta.repro.newCanvas=<rounds> is set: creates a new canvas over and
 * over in the real shell, which is the one place the "RootNodeOwner is already disposed" crash
 * has ever been seen.
 */
@Composable
internal fun DesktopCanvasCrashReproHook(canvasShell: DesktopCanvasShell) {
    val reproRounds = remember { System.getProperty("letta.repro.newCanvas")?.toIntOrNull() ?: 0 }
    var reproMenuOpen by remember { mutableStateOf(false) }
    if (reproRounds <= 0) return

    androidx.compose.material3.DropdownMenu(
        expanded = reproMenuOpen,
        onDismissRequest = { reproMenuOpen = false },
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { androidx.compose.material3.Text("Open canvas") },
            onClick = { reproMenuOpen = false },
        )
    }
    LaunchedEffect(Unit) {
        delay(REPRO_SETTLE_MS)
        repeat(reproRounds) { index ->
            println("REPRO-ROUND $index")
            reproMenuOpen = true
            delay(REPRO_MENU_MS)
            reproMenuOpen = false
            canvasShell.openForConversation(
                DesktopCanvasOwner(
                    conversationId = "repro-conversation-$index",
                    agentId = null,
                    agentName = "Repro",
                ),
            )
            delay(REPRO_STEP_MS)
            canvasShell.close()
            delay(REPRO_STEP_MS)
        }
        println("REPRO-CLEAN: $reproRounds rounds, no crash")
        exitProcess(0)
    }
}

private const val REPRO_SETTLE_MS = 6000L
private const val REPRO_STEP_MS = 900L
private const val REPRO_MENU_MS = 250L
