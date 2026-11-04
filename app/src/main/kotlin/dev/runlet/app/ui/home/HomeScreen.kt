package dev.runlet.app.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.runlet.app.RoutineSummary
import dev.runlet.app.ui.components.EmptyState
import dev.runlet.app.ui.components.MonogramTile
import dev.runlet.app.ui.components.Pill
import dev.runlet.app.ui.components.SectionLabel
import dev.runlet.app.ui.components.formatDuration
import dev.runlet.app.ui.components.stepsLabel
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons

@Composable
fun HomeScreen(
    routines: List<RoutineSummary>,
    runningName: String?,
    onOpen: (Long) -> Unit,
    onRun: (Long) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onBackup: () -> Unit,
    onDuplicate: (Long) -> Unit,
    onShare: (Long) -> Unit,
    onCopy: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onToggleEnabled: (Long, Boolean) -> Unit,
    exactAlarmMissing: Boolean,
    onAllowExactAlarms: () -> Unit,
    contentPadding: PaddingValues,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 112.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("home-list"),
        ) {
            item { Header(count = routines.size, onImport = onImport, onPaste = onPaste, onBackup = onBackup, canBackUp = routines.isNotEmpty()) }
            if (exactAlarmMissing) item { ExactAlarmBanner(onAllowExactAlarms) }
            if (routines.isEmpty()) {
                item {
                    EmptyState(
                        icon = RunletIcons.Timer,
                        title = "No routines yet",
                        body = "Create one from a template, or import a .routine file from another phone.",
                    )
                }
            } else {
                item { SectionLabel("Your routines", Modifier.padding(top = 4.dp)) }
                items(routines, key = { it.id }) { r ->
                    RoutineCard(
                        r = r,
                        isRunning = r.name == runningName,
                        onOpen = { onOpen(r.id) },
                        onRun = { onRun(r.id) },
                        onDuplicate = { onDuplicate(r.id) },
                        onShare = { onShare(r.id) },
                        onCopy = { onCopy(r.id) },
                        onExport = { onExport(r.id) },
                        onDelete = { onDelete(r.id) },
                        onToggleEnabled = { onToggleEnabled(r.id, it) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onNew,
            icon = { Icon(RunletIcons.Add, contentDescription = null) },
            text = { Text("New routine") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = contentPadding.calculateBottomPadding() + 20.dp)
                .testTag("new-routine"),
        )
    }
}

@Composable
private fun Header(count: Int, onImport: () -> Unit, onPaste: () -> Unit, onBackup: () -> Unit, canBackUp: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "ON-DEVICE AUTOMATION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text("Runlet", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (count == 0) "Timers and routines that run in the background"
                else if (count == 1) "1 routine, ready when you are" else "$count routines, ready when you are",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("home-menu")) {
                Icon(RunletIcons.Upload, contentDescription = "Import and back up")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Import from file") }, leadingIcon = { Icon(RunletIcons.Upload, null) },
                    onClick = { menu = false; onImport() }, modifier = Modifier.testTag("menu-import"),
                )
                DropdownMenuItem(
                    text = { Text("Paste from clipboard") }, leadingIcon = { Icon(RunletIcons.Copy, null) },
                    onClick = { menu = false; onPaste() }, modifier = Modifier.testTag("menu-paste"),
                )
                DropdownMenuItem(
                    text = { Text("Back up all routines") }, leadingIcon = { Icon(RunletIcons.Download, null) },
                    enabled = canBackUp, onClick = { menu = false; onBackup() }, modifier = Modifier.testTag("menu-backup"),
                )
            }
        }
    }
}

/** Shown while an armed routine has a clock-time trigger but exact alarms are not allowed. */
@Composable
private fun ExactAlarmBanner(onAllow: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().testTag("exact-alarm-banner"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Timed routines may start late", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android only runs them on the minute if you allow exact alarms. Without it they can be up to an hour late.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onAllow, modifier = Modifier.testTag("exact-alarm-allow")) { Text("Allow exact alarms") }
        }
    }
}

@Composable
private fun RoutineCard(
    r: RoutineSummary,
    isRunning: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val runnable = r.error == null
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("routine-card").animateContentSize(),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonogramTile(r.name)
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        r.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("routine-name"),
                    )
                    Text(
                        if (runnable) "${stepsLabel(r.steps)} · ${formatDuration(r.totalMs)}" else "Needs attention",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (runnable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                FilledIconButton(
                    onClick = onRun,
                    enabled = runnable,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(44.dp).testTag("routine-run").semantics { contentDescription = "Run ${r.name}" },
                ) { Icon(RunletIcons.Play, contentDescription = null) }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("routine-menu")) {
                        Icon(RunletIcons.MoreVert, contentDescription = "More actions for ${r.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") }, leadingIcon = { Icon(RunletIcons.Edit, null) },
                            onClick = { menu = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") }, leadingIcon = { Icon(RunletIcons.Copy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") }, leadingIcon = { Icon(RunletIcons.Upload, null) },
                            onClick = { menu = false; onShare() }, modifier = Modifier.testTag("menu-share"),
                        )
                        DropdownMenuItem(
                            text = { Text("Copy script") }, leadingIcon = { Icon(RunletIcons.Copy, null) },
                            onClick = { menu = false; onCopy() }, modifier = Modifier.testTag("menu-copy"),
                        )
                        DropdownMenuItem(
                            text = { Text("Save to file") }, leadingIcon = { Icon(RunletIcons.Download, null) },
                            onClick = { menu = false; onExport() }, modifier = Modifier.testTag("menu-export"),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(RunletIcons.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                            modifier = Modifier.testTag("menu-delete"),
                        )
                    }
                }
            }
            if (runnable && r.stages.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                StageStrip(r.stages)
            }
            if (runnable && r.triggers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.triggers.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = r.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.testTag("routine-enabled").semantics { contentDescription = "Run ${r.name} automatically" },
                    )
                }
            }
            if (isRunning) {
                Spacer(Modifier.height(12.dp))
                Pill("Running now", Runlet.extras.success, icon = RunletIcons.Timer)
            } else if (!runnable) {
                Spacer(Modifier.height(10.dp))
                Text(
                    r.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A row of chips naming the routine's stages, so a card says what the routine *is*, not just how long it is. */
@Composable
private fun StageStrip(stages: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        stages.take(3).forEach { s ->
            Text(
                s,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (stages.size > 3) {
            Text(
                "+${stages.size - 3}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
            )
        }
    }
}
