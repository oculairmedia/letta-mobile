package com.letta.mobile.ui.memory

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.data.memory.graph.MemoryGraphTuning
import com.letta.mobile.data.memory.graph.MemoryGraphTunings
import com.letta.mobile.ui.theme.LettaDimens
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/**
 * The dev-only memory graph tuning window: every knob of [MemoryGraphTuning] live against the open
 * Memory graph (one agent's or the fleet's). The desktop opens it only when `MERIDIAN_GRAPH_LOOKDEV=1`;
 * Ctrl+Shift+G hides and shows it. The tuned values persist in [store] (a dev data file) and are
 * loaded back on the next launch; nothing else ever reads that file.
 */
@Composable
fun MemoryGraphTuningWindow(store: Path, tunings: MemoryGraphTunings) {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(store, tunings) {
        withContext(Dispatchers.IO) { MemoryGraphTuningStore.load(store) }?.let(tunings::set)
        tunings.current.drop(1).collectLatest { tuning ->
            delay(PERSIST_DELAY_MS)
            withContext(Dispatchers.IO) { MemoryGraphTuningStore.save(store, tuning) }
        }
    }
    ToggleShortcut { visible = !visible }
    if (!visible) return
    Window(
        onCloseRequest = { visible = false },
        title = "Memory graph tuning (dev, Ctrl+Shift+G)",
        state = rememberWindowState(width = LettaDimens.Pane.wideBreakpoint, height = LettaDimens.Pane.wideBreakpoint),
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                val tuning by tunings.current.collectAsState()
                MemoryGraphTuningPanel(tuning, devActions(tunings))
            }
        }
    }
}

private fun devActions(tunings: MemoryGraphTunings) = MemoryGraphTuningActions(
    onChange = tunings::set,
    onReset = tunings::reset,
    onReheat = tunings::reheat,
    onCopy = {
        val source = MemoryGraphTunings.kotlinSource(tunings.current.value)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(source), null)
    },
)

/** Ctrl+Shift+G anywhere in the app runs [onToggle], for as long as the window is composed. */
@Composable
private fun ToggleShortcut(onToggle: () -> Unit) {
    DisposableEffect(Unit) {
        val dispatcher = KeyEventDispatcher { event ->
            val pressed = event.id == KeyEvent.KEY_PRESSED && event.keyCode == KeyEvent.VK_G
            (pressed && event.isControlDown && event.isShiftDown).also { if (it) onToggle() }
        }
        val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focus.addKeyEventDispatcher(dispatcher)
        onDispose { focus.removeKeyEventDispatcher(dispatcher) }
    }
}

/** The dev tuning file: JSON in the app's dev data directory. A missing or unreadable file is no tuning. */
internal object MemoryGraphTuningStore {
    fun load(store: Path): MemoryGraphTuning? =
        runCatching { Files.readString(store) }.getOrNull()?.let(MemoryGraphTunings::decode)

    fun save(store: Path, tuning: MemoryGraphTuning) {
        runCatching {
            store.parent?.let { Files.createDirectories(it) }
            Files.writeString(store, MemoryGraphTunings.encode(tuning))
        }
    }
}

private const val PERSIST_DELAY_MS = 300L
