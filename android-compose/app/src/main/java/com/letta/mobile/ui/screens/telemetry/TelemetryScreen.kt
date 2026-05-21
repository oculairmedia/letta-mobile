package com.letta.mobile.ui.screens.telemetry

import com.letta.mobile.ui.theme.LettaCodeFont

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.util.Telemetry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Dev-mode telemetry inspector.
 *
 * Shows the most recent ~1000 events from [Telemetry]'s ring buffer, with
 * filters by tag and log level. Events are rendered newest-first with
 * monospace formatting for quick visual scanning of latencies and attrs.
 *
 * Actions:
 *  - Copy all (as text) to clipboard
 *  - Clear the buffer
 *  - Filter by tag (Http, TimelineSync, AdminChatVM, etc.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val events by Telemetry.events.collectAsStateWithLifecycle()

    var tagFilter by remember { mutableStateOf<String?>(null) }
    var filterMenuOpen by remember { mutableStateOf(false) }

    val allTags = remember(events) { events.map { it.tag }.distinct().sorted() }
    val visibleEvents = remember(events, tagFilter) {
        if (tagFilter == null) events else events.filter { it.tag == tagFilter }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Telemetry")
                        Text(
                            "${visibleEvents.size} / ${events.size} events" +
                                (tagFilter?.let { " · tag=$it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { filterMenuOpen = true }) {
                            Text(tagFilter ?: "All tags")
                        }
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("All tags") },
                                onClick = {
                                    tagFilter = null
                                    filterMenuOpen = false
                                },
                            )
                            allTags.forEach { tag ->
                                DropdownMenuItem(
                                    text = { Text(tag) },
                                    onClick = {
                                        tagFilter = tag
                                        filterMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { copyToClipboard(context) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                    }
                    IconButton(onClick = { Telemetry.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TimelineDumpToggleRow()
            HorizontalDivider()
            TelemetryEventList(visibleEvents = visibleEvents, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TimelineDumpToggleRow() {
    var enabled by remember { mutableStateOf(Telemetry.isTimelineDumpEnabled()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Timeline state dump",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Logs every timeline event after hydrate / reconcile / stream-ingest. " +
                    "High-volume — leave off unless diagnosing hydration dupes (1ar3u / 3j6 / 16li).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = { newValue ->
                Telemetry.timelineDumpEnabled.set(newValue)
                enabled = newValue
            },
        )
    }
}

@Composable
private fun TelemetryEventList(
    visibleEvents: List<Telemetry.Event>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (visibleEvents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No events yet — interact with the app to populate the buffer.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Use itemsIndexed with an index suffix: multiple telemetry
                // events can fire in the same millisecond with the same
                // tag/name (e.g. repeated `Http/request`). Without the
                // index suffix the LazyColumn crashes with a duplicate-key
                // IllegalArgumentException.
                itemsIndexed(
                    items = visibleEvents,
                    key = { index, ev -> "${ev.timestampMs}/${ev.tag}/${ev.name}#$index" },
                ) { _, ev -> TelemetryEventRow(ev) }
            }
        }
    }
}

@Composable
private fun TelemetryEventRow(ev: Telemetry.Event) {
    val levelColor = when (ev.level) {
        Telemetry.Level.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        Telemetry.Level.INFO -> MaterialTheme.colorScheme.onSurface
        Telemetry.Level.WARN -> Color(0xFFFFA000)
        Telemetry.Level.ERROR -> MaterialTheme.colorScheme.error
    }
    val bgColor = when (ev.level) {
        Telemetry.Level.WARN -> Color(0x22FFA000)
        Telemetry.Level.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                formatTime(ev.timestampMs),
                fontSize = 11.sp,
                fontFamily = LettaCodeFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${ev.tag}/${ev.name}",
                fontSize = 13.sp,
                fontFamily = LettaCodeFont,
                fontWeight = FontWeight.Medium,
                color = levelColor,
                modifier = Modifier.weight(1f),
            )
            val duration = ev.durationMs
            if (duration != null) {
                Text(
                    "${duration}ms",
                    fontSize = 12.sp,
                    fontFamily = LettaCodeFont,
                    fontWeight = FontWeight.Bold,
                    color = durationColor(duration),
                )
            }
        }
        if (ev.attrs.isNotEmpty()) {
            Text(
                ev.attrs.entries.joinToString("  ") { (k, v) -> "$k=$v" },
                fontSize = 11.sp,
                fontFamily = LettaCodeFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ev.throwable?.let { t ->
            Text(
                "${t.javaClass.simpleName}: ${t.message ?: ""}",
                fontSize = 11.sp,
                fontFamily = LettaCodeFont,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Color duration badge based on magnitude — quick visual scanning. */
private fun durationColor(ms: Long): Color = when {
    ms >= 2000 -> Color(0xFFD32F2F)  // red
    ms >= 500 -> Color(0xFFFFA000)   // amber
    ms >= 100 -> Color(0xFF689F38)   // green
    else -> Color(0xFF388E3C)        // deep green
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
private fun formatTime(ms: Long): String = timeFmt.format(Instant.ofEpochMilli(ms))

private fun copyToClipboard(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("telemetry", Telemetry.exportText()))
    Toast.makeText(context, "Copied telemetry to clipboard", Toast.LENGTH_SHORT).show()
}
