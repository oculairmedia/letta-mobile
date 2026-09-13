package com.letta.mobile.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.rive.RiveAvatarRuntime
import com.letta.mobile.avatar.rive.RiveAvatarSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Debug-only harness for the Rive mascot spike (letta-mobile avatar renderer).
 *
 * Debug source set on purpose: it is a bench, not a feature, and nothing here should be reachable
 * from a shipped build. Launch it with:
 *
 * ```
 * adb shell am start -n com.letta.mobile.dev/com.letta.mobile.debug.MascotDebugActivity
 * ```
 *
 * What it proves is the whole chain, not just the renderer: the buttons drive [AvatarDirector],
 * the director drives the shared [RiveAvatarRuntime] through the ordinary avatar commands, and the
 * runtime writes the Rive view model. Nothing here writes a mascot state directly, because a bench
 * that bypasses the director would prove the one link that was never in doubt.
 */
class MascotDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { MascotBench() }
            }
        }
    }
}

@Composable
private fun MascotBench() {
    var runtime by remember { mutableStateOf<RiveAvatarRuntime?>(null) }
    var state by remember { mutableStateOf(AvatarState.IDLE) }
    val director = remember(runtime) { runtime?.let { AvatarDirector(it) } }

    // The director arbitrates; the surface renders whatever it decided. Reading the state back
    // through the listener is what makes this a test of the director rather than of the buttons.
    LaunchedEffect(director) {
        director?.addStateListener { _, enter -> state = enter }
    }

    // Blinks and mouth decay are time-based, so the director needs a clock or the mascot is frozen
    // between button presses.
    LaunchedEffect(director) {
        val d = director ?: return@LaunchedEffect
        var last = System.nanoTime()
        while (isActive) {
            delay(16)
            val now = System.nanoTime()
            d.tick((now - last) / 1_000_000_000f)
            last = now
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Director state: $state", style = MaterialTheme.typography.titleMedium)

        RiveAvatarSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().height(320.dp),
            onRuntime = { runtime = it },
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarActivity.entries.forEach { activity ->
                Button(onClick = { director?.setActivity(activity) }) { Text(activity.name) }
            }
        }
    }
}
