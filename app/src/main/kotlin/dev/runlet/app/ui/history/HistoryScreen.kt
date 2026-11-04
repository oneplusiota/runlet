package dev.runlet.app.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.runlet.app.ui.components.EmptyState
import dev.runlet.app.ui.components.IconTile
import dev.runlet.app.ui.components.formatDuration
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.app.ui.theme.RunletText
import dev.runlet.core.data.RunEntity
import dev.runlet.core.data.RunLogEntity
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    runs: List<RunEntity>,
    openRunId: Long?,
    logs: List<RunLogEntity>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().testTag("history-list"),
    ) {
        item {
            Text("Activity", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                if (runs.isEmpty()) "Everything your routines do shows up here"
                else "Your last ${runs.size} ${if (runs.size == 1) "run" else "runs"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (runs.isEmpty()) {
            item {
                Box(Modifier.testTag("no-runs")) {
                    EmptyState(
                        icon = RunletIcons.History,
                        title = "No runs yet",
                        body = "Run a routine and its steps, timings and any problems are recorded here.",
                    )
                }
            }
        } else {
            items(runs, key = { it.id }) { run ->
                RunCard(run, expanded = run.id == openRunId, logs = if (run.id == openRunId) logs else emptyList(), onClick = { onToggle(run.id) })
            }
            item {
                OutlinedButton(onClick = onClear, modifier = Modifier.padding(top = 6.dp).testTag("clear-history")) {
                    Icon(RunletIcons.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear history")
                }
            }
        }
    }
}

private fun statusLook(status: String, x: dev.runlet.app.ui.theme.RunletExtras): Triple<String, Color, androidx.compose.ui.graphics.vector.ImageVector> = when (status) {
    "COMPLETED" -> Triple("Completed", x.success, RunletIcons.CheckCircle)
    "RUNNING" -> Triple("Running", x.stage, RunletIcons.Timer)
    "PAUSED" -> Triple("Paused", x.sound, RunletIcons.Pause)
    "ERROR" -> Triple("Failed", x.warning, RunletIcons.Warning)
    "SKIPPED" -> Triple("Skipped", x.warning, RunletIcons.Warning)
    else -> Triple("Stopped", x.note, RunletIcons.Stop)
}

@Composable
private fun RunCard(run: RunEntity, expanded: Boolean, logs: List<RunLogEntity>, onClick: () -> Unit) {
    val x = Runlet.extras
    val (label, tint, icon) = statusLook(run.status, x)
    val whenText = remember(run.startedAtMs) { relativeDay(run.startedAtMs) }
    val took = run.endedAtMs?.let { (it - run.startedAtMs).coerceAtLeast(0) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("run-row"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, tint, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(run.automationName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append(whenText).append(" · ").append(label)
                            if (took != null && run.status == "COMPLETED") append(" in ").append(formatDuration(took))
                            if (run.error != null) append(" · ").append(run.error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) RunletIcons.ExpandLess else RunletIcons.ExpandMore, contentDescription = if (expanded) "Hide log" else "Show log")
            }
            AnimatedVisibility(visible = expanded) {
                SelectionContainer {
                    Column(Modifier.padding(top = 14.dp).fillMaxWidth().testTag("run-log")) {
                        if (logs.isEmpty()) {
                            Text("No log lines were recorded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                        logs.forEach { l ->
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(fmt.format(Date(l.timestampMs)), style = RunletText.code.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Text(l.message, style = RunletText.code.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun relativeDay(ms: Long, now: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
    val sameDay = cal.get(Calendar.YEAR) == then.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today, $time"
    cal.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = cal.get(Calendar.YEAR) == then.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Yesterday, $time"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}
