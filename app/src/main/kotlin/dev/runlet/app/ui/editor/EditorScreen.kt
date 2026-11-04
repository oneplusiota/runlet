package dev.runlet.app.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.runlet.app.Validation
import dev.runlet.app.ui.components.IconTile
import dev.runlet.app.ui.components.Pill
import dev.runlet.app.ui.components.SectionLabel
import dev.runlet.app.ui.components.actionLook
import dev.runlet.app.ui.components.clockText
import dev.runlet.app.ui.components.formatDuration
import dev.runlet.app.ui.components.repeatTint
import dev.runlet.app.ui.components.noteTint
import dev.runlet.app.ui.components.stepsLabel
import dev.runlet.app.ui.components.style
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.app.ui.theme.RunletText
import dev.runlet.core.engine.ActionRegistry
import dev.runlet.core.engine.Outline
import dev.runlet.core.engine.OutlineItem
import dev.runlet.core.engine.actionCount
import dev.runlet.core.engine.totalMs
import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Day
import dev.runlet.core.model.Trigger
import dev.runlet.core.engine.ScriptPrinter
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState

/** A step's position in the outline: indices from the top level down into repeat blocks. */
typealias Path = List<Int>

@Composable
fun EditorContent(
    outline: Outline?,
    text: String,
    validation: Validation,
    advanced: Boolean,
    onTextChange: (String) -> Unit,
    onEditOutline: ((Outline) -> Outline) -> Unit,
    onAlerts: (AlertMode) -> Unit,
    contentPadding: PaddingValues,
) {
    AnimatedContent(targetState = advanced && true, label = "editor-mode", modifier = Modifier.fillMaxSize()) { isAdvanced ->
        if (isAdvanced || outline == null) {
            AdvancedEditor(text, validation, onTextChange, contentPadding, showFallbackNotice = !isAdvanced)
        } else {
            BasicEditor(outline, validation, onEditOutline, onAlerts, contentPadding)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Basic editor: a list of step cards with an "add step" sheet
// ------------------------------------------------------------------------------------------------

private sealed interface Sheet {
    /** Adding into the container at [parent] (empty = top level). */
    data class Add(val parent: Path) : Sheet
    data class EditStep(val path: Path, val action: Action) : Sheet
    data class EditRepeat(val path: Path, val count: Int) : Sheet
    /** Adding a trigger ([index] null) or editing the one at [index]. */
    data class EditTrigger(val index: Int?, val trigger: Trigger?) : Sheet
}

@Composable
private fun BasicEditor(
    outline: Outline,
    validation: Validation,
    onEdit: ((Outline) -> Outline) -> Unit,
    onAlerts: (AlertMode) -> Unit,
    contentPadding: PaddingValues,
) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val rows = remember(outline) { flatten(outline.items, emptyList(), 0) }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("basic-editor"),
    ) {
        item { Summary(outline, validation) }
        item { AlertsRow(outline.alerts, onAlerts) }
        item {
            WhenSection(
                triggers = outline.triggers,
                onAdd = { sheet = Sheet.EditTrigger(null, null) },
                onEdit = { i -> sheet = Sheet.EditTrigger(i, outline.triggers[i]) },
                onDelete = { i -> onEdit { it.removeTrigger(i) } },
            )
        }
        item { SectionLabel("Steps", Modifier.padding(top = 8.dp, bottom = 2.dp)) }

        // A routine holding only notes (like the yoga preset) has nothing to run, so it gets the same invitation.
        if (rows.none { it is Row.Step || it is Row.Header }) {
            item { EmptySteps { sheet = Sheet.Add(emptyList()) } }
        }
        itemsIndexed(rows, key = { _, r -> r.key }) { _, row ->
            when (row) {
                is Row.Step -> StepCard(
                    row = row,
                    onClick = { sheet = Sheet.EditStep(row.path, row.action) },
                    onMove = { d -> onEdit { it.move(row.path, d) } },
                    onDelete = { onEdit { it.remove(row.path) } },
                )
                is Row.Header -> RepeatHeader(
                    row = row,
                    onClick = { sheet = Sheet.EditRepeat(row.path, row.count) },
                    onMove = { d -> onEdit { it.move(row.path, d) } },
                    onDelete = { onEdit { it.remove(row.path) } },
                )
                is Row.Note -> NoteRow(row, onDelete = { onEdit { it.remove(row.path) } })
                is Row.AddInside -> AddInsideRow(row.depth) { sheet = Sheet.Add(row.parent) }
            }
        }
        item {
            FilledTonalButton(
                onClick = { sheet = Sheet.Add(emptyList()) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp).testTag("add-step"),
            ) {
                Icon(RunletIcons.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a step")
            }
        }
    }

    when (val s = sheet) {
        null -> Unit
        is Sheet.Add -> AddStepSheet(
            onDismiss = { sheet = null },
            onPick = { item ->
                sheet = null
                onEdit { o -> o.insert(s.parent, Int.MAX_VALUE, item) }
            },
        )
        is Sheet.EditStep -> StepEditorSheet(
            action = s.action,
            onDismiss = { sheet = null },
            onSave = { updated ->
                sheet = null
                onEdit { it.replace(s.path, OutlineItem.Step(updated)) }
            },
        )
        is Sheet.EditTrigger -> TriggerSheet(
            initial = s.trigger,
            onDismiss = { sheet = null },
            onSave = { t ->
                sheet = null
                onEdit { o -> if (s.index == null) o.addTrigger(t) else o.replaceTrigger(s.index, t) }
            },
        )
        is Sheet.EditRepeat -> RepeatSheet(
            count = s.count,
            onDismiss = { sheet = null },
            onSave = { n ->
                sheet = null
                onEdit { o ->
                    val old = o.itemAt(s.path) as? OutlineItem.Repeat
                    if (old == null) o else o.replace(s.path, old.copy(count = n))
                }
            },
        )
    }
}

@Composable
private fun Summary(outline: Outline, validation: Validation) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TOTAL TIME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (validation is Validation.Ok) formatDuration(validation.totalMs) else formatDuration(outline.totalMs),
                    style = RunletText.timerMedium,
                    modifier = Modifier.testTag("total-time"),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stepsLabel(outline.actionCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), style = MaterialTheme.typography.titleMedium)
                when (validation) {
                    is Validation.Ok -> Pill("Ready to run", Runlet.extras.success, icon = RunletIcons.Check)
                    is Validation.Error -> Pill(
                        if (outline.items.isEmpty()) "Add a step" else "Can't run yet",
                        Runlet.extras.warning, icon = RunletIcons.Warning,
                    )
                }
            }
        }
    }
    if (validation is Validation.Error && outline.items.isNotEmpty() && validation.message.isNotBlank()) {
        Text(
            validation.message,
            style = MaterialTheme.typography.bodySmall,
            color = Runlet.extras.warning,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp).testTag("validation"),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertsRow(current: AlertMode, onPick: (AlertMode) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        SectionLabel("Alert at the start of each stage")
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            listOf(
                AlertMode.BOTH to "Both",
                AlertMode.SOUND to "Sound",
                AlertMode.VIBRATION to "Vibrate",
                AlertMode.NONE to "Silent",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = mode == current,
                    onClick = { onPick(mode) },
                    label = { Text(label) },
                    modifier = Modifier.testTag("alerts-${mode.keyword}"),
                )
            }
        }
    }
}

/** "When" cards: what starts this routine by itself. Empty means it only runs when you press play. */
@Composable
private fun WhenSection(triggers: List<Trigger>, onAdd: () -> Unit, onEdit: (Int) -> Unit, onDelete: (Int) -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("When")
        if (triggers.isEmpty()) {
            Text(
                "Runs when you press play. Add a time to start it automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        triggers.forEachIndexed { i, t ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().testTag("trigger-card"),
                // A boot trigger has nothing to edit.
                onClick = { if (t is Trigger.Time) onEdit(i) },
            ) {
                Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(RunletIcons.Timer, Runlet.extras.stage, size = 40.dp, iconSize = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(ScriptPrinter.describe(t), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onDelete(i) }, modifier = Modifier.size(40.dp).testTag("trigger-delete")) {
                        Icon(RunletIcons.Delete, contentDescription = "Remove trigger", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.testTag("add-trigger")) {
            Icon(RunletIcons.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add a trigger")
        }
    }
}

private val DAY_LABELS = mapOf(
    Day.MON to "M", Day.TUE to "T", Day.WED to "W", Day.THU to "T", Day.FRI to "F", Day.SAT to "S", Day.SUN to "S",
)

/** Pick a time and days, or "when the phone starts". Editing an existing time trigger skips the type choice. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TriggerSheet(initial: Trigger?, onDismiss: () -> Unit, onSave: (Trigger) -> Unit) {
    val start = initial as? Trigger.Time
    var kind by remember { mutableStateOf(if (initial == Trigger.Boot) "boot" else "time") }
    var days by remember { mutableStateOf(start?.days ?: Day.ALL) }
    val time = rememberTimePickerState(start?.hour ?: 8, start?.minute ?: 0, is24Hour = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("trigger-sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("When should it run?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
            if (initial == null) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "time", onClick = { kind = "time" }, label = { Text("At a time") }, modifier = Modifier.testTag("kind-time"))
                    FilterChip(selected = kind == "boot", onClick = { kind = "boot" }, label = { Text("Phone starts") }, modifier = Modifier.testTag("kind-boot"))
                }
            }
            if (kind == "time") {
                Spacer(Modifier.height(16.dp))
                TimePicker(state = time)
                // One row of equal chips: seven letters always fit, so no stray wrapped chip.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Day.entries.forEach { d ->
                        FilterChip(
                            selected = d in days,
                            onClick = { days = if (d in days) days - d else days + d },
                            label = { Text(DAY_LABELS.getValue(d), Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                            modifier = Modifier.weight(1f).semantics { contentDescription = d.keyword }.testTag("day-${d.keyword}"),
                        )
                    }
                }
                if (days.isEmpty()) {
                    Text("Pick at least one day.", style = MaterialTheme.typography.bodySmall, color = Runlet.extras.warning)
                }
            } else {
                Text(
                    "Runs a moment after the phone finishes starting up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { onSave(if (kind == "boot") Trigger.Boot else Trigger.Time(time.hour, time.minute, days)) },
                    enabled = kind == "boot" || days.isNotEmpty(),
                    modifier = Modifier.testTag("trigger-save"),
                ) { Text("Done") }
            }
        }
    }
}

@Composable
private fun EmptySteps(onAdd: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("empty-steps"),
        onClick = onAdd,
    ) {
        Column(Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconTile(RunletIcons.Flag, Runlet.extras.stage, size = 56.dp, iconSize = 28.dp)
            Spacer(Modifier.height(14.dp))
            Text("This routine is empty", style = MaterialTheme.typography.titleMedium)
            Text(
                "Start with a stage — a named step with a time, like “Bloom, 30 s”.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepCard(row: Row.Step, onClick: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val s = row.action.style()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp * row.depth).testTag("step-card"),
        onClick = onClick,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(s.icon, s.tint, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    s.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MoveButtons(row.canMoveUp, row.canMoveDown, onMove)
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp).testTag("step-delete")) {
                Icon(RunletIcons.Delete, contentDescription = "Remove ${s.title}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MoveButtons(canUp: Boolean, canDown: Boolean, onMove: (Int) -> Unit) {
    Column {
        IconButton(onClick = { onMove(-1) }, enabled = canUp, modifier = Modifier.size(width = 36.dp, height = 24.dp).testTag("step-up")) {
            Icon(RunletIcons.ExpandLess, contentDescription = "Move up", Modifier.size(20.dp))
        }
        IconButton(onClick = { onMove(+1) }, enabled = canDown, modifier = Modifier.size(width = 36.dp, height = 24.dp).testTag("step-down")) {
            Icon(RunletIcons.ExpandMore, contentDescription = "Move down", Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RepeatHeader(row: Row.Header, onClick: () -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit) {
    val tint = repeatTint()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = tint.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp * row.depth).testTag("repeat-header"),
        onClick = onClick,
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(RunletIcons.Repeat, tint, size = 40.dp, iconSize = 20.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Repeat ${row.count} ${if (row.count == 1) "time" else "times"}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${stepsLabel(row.innerSteps)} each round · ${formatDuration(row.roundMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoveButtons(row.canMoveUp, row.canMoveDown, onMove)
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp).testTag("repeat-delete")) {
                Icon(RunletIcons.Delete, contentDescription = "Remove repeat block", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddInsideRow(depth: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(start = 18.dp * depth).testTag("add-inside")) {
        Icon(RunletIcons.Add, null, Modifier.size(16.dp), tint = repeatTint())
        Spacer(Modifier.width(6.dp))
        Text("Add to this repeat", color = repeatTint())
    }
}

@Composable
private fun NoteRow(row: Row.Note, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp * row.depth + 4.dp).testTag("note-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(RunletIcons.Note, null, Modifier.size(16.dp), tint = noteTint())
        Spacer(Modifier.width(8.dp))
        Text(
            row.text.trimStart('#', ' '),
            style = MaterialTheme.typography.bodySmall,
            color = noteTint(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(RunletIcons.Close, contentDescription = "Remove note", Modifier.size(16.dp), tint = noteTint())
        }
    }
}

/** One row in the flattened view of the outline. [key] is stable across edits that don't touch the row. */
private sealed interface Row {
    val key: String
    val path: Path

    data class Step(
        override val path: Path, val action: Action, val depth: Int, val canMoveUp: Boolean, val canMoveDown: Boolean,
    ) : Row { override val key get() = "s" + path.joinToString(".") }

    data class Header(
        override val path: Path, val count: Int, val depth: Int, val innerSteps: Int, val roundMs: Long,
        val canMoveUp: Boolean, val canMoveDown: Boolean,
    ) : Row { override val key get() = "r" + path.joinToString(".") }

    data class Note(override val path: Path, val text: String, val depth: Int) : Row {
        override val key get() = "n" + path.joinToString(".")
    }

    data class AddInside(val parent: Path, val depth: Int) : Row {
        override val path get() = parent
        override val key get() = "a" + parent.joinToString(".")
    }
}

private fun flatten(items: List<OutlineItem>, parent: Path, depth: Int): List<Row> = buildList {
    items.forEachIndexed { i, item ->
        val path = parent + i
        when (item) {
            is OutlineItem.Step -> add(Row.Step(path, item.action, depth, i > 0, i < items.lastIndex))
            is OutlineItem.Comment -> add(Row.Note(path, item.text, depth))
            is OutlineItem.Repeat -> {
                add(
                    Row.Header(
                        path, item.count, depth, item.items.actionCount().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        item.items.totalMs(), i > 0, i < items.lastIndex,
                    ),
                )
                addAll(flatten(item.items, path, depth + 1))
                add(Row.AddInside(path, depth + 1))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Sheets
// ------------------------------------------------------------------------------------------------

private data class NewStepOption(val id: String, val title: String, val hint: String, val build: () -> OutlineItem)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStepSheet(onDismiss: () -> Unit, onPick: (OutlineItem) -> Unit) {
    val x = Runlet.extras
    // Every registered action appears here automatically; Repeat is a script construct, not an action, so it is added by hand.
    val options = remember {
        val registry = ActionRegistry.builtIn
        val actions = registry.all().map { type ->
            NewStepOption(type.id, type.title, type.hint) { OutlineItem.Step(type.newAction()) }
        }
        val repeat = NewStepOption("repeat", "Repeat", "Loop a group of steps") {
            OutlineItem.Repeat(3, listOf(OutlineItem.Step(registry.type("stage")!!.newAction())))
        }
        // Stage and wait first, then repeat, then the rest, as before.
        actions.take(2) + repeat + actions.drop(2)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("add-sheet")) {
            Text("Add a step", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            options.forEach { o ->
                val (icon, tint) = if (o.id == "repeat") RunletIcons.Repeat to x.repeat else actionLook(o.id)
                Surface(
                    onClick = { onPick(o.build()) },
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("option-${o.title}"),
                ) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(icon, tint)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(o.title, style = MaterialTheme.typography.titleSmall)
                            Text(o.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepEditorSheet(action: Action, onDismiss: () -> Unit, onSave: (Action) -> Unit) {
    val s = action.style()
    var label by remember { mutableStateOf((action as? Action.Stage)?.label ?: (action as? Action.Notify)?.message ?: (action as? Action.Log)?.message ?: "") }
    var seconds by remember { mutableStateOf(secondsOf(action)) }
    var percent by remember { mutableStateOf((action as? Action.Volume)?.percent ?: 50) }

    val built: Action? = when (action) {
        is Action.Stage -> if (label.isNotBlank() && !label.contains('"') && seconds > 0) Action.Stage(label.trim(), seconds * 1000L) else null
        is Action.Wait -> if (seconds > 0) Action.Wait(seconds * 1000L) else null
        is Action.Vibrate -> if (seconds in 1..5) Action.Vibrate(seconds * 1000L) else null
        is Action.Notify -> if (label.isNotBlank() && !label.contains('"')) Action.Notify(label.trim()) else null
        is Action.Log -> if (label.isNotBlank() && !label.contains('"')) Action.Log(label.trim()) else null
        is Action.Volume -> Action.Volume(percent)
        Action.Chime -> Action.Chime
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("step-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(s.icon, s.tint)
                Spacer(Modifier.width(14.dp))
                Text(
                    when (action) {
                        is Action.Stage -> "Edit stage"
                        is Action.Wait -> "Edit wait"
                        is Action.Notify -> "Edit notification"
                        is Action.Vibrate -> "Edit vibration"
                        is Action.Volume -> "Edit volume"
                        is Action.Log -> "Edit log entry"
                        Action.Chime -> "Chime"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(18.dp))

            when (action) {
                is Action.Stage, is Action.Notify, is Action.Log -> {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.replace("\"", "").replace("\n", " ").take(160) },
                        label = { Text(if (action is Action.Stage) "Name" else "Message") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("field-label"),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                else -> Unit
            }
            when (action) {
                is Action.Stage, is Action.Wait -> DurationPicker(seconds, { seconds = it }, maxSeconds = 4 * 3600)
                is Action.Vibrate -> {
                    SectionLabel("Length")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { n ->
                            FilterChip(selected = seconds == n, onClick = { seconds = n }, label = { Text("$n s") })
                        }
                    }
                }
                is Action.Volume -> VolumePicker(percent) { percent = it }
                else -> Unit
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { built?.let(onSave) }, enabled = built != null, modifier = Modifier.testTag("step-save")) {
                    Text("Done")
                }
            }
        }
    }
}

private fun secondsOf(a: Action): Int = when (a) {
    is Action.Stage -> (a.durationMs / 1000).toInt().coerceAtLeast(1)
    is Action.Wait -> (a.durationMs / 1000).toInt().coerceAtLeast(1)
    is Action.Vibrate -> (a.durationMs / 1000).toInt().coerceIn(1, 5)
    else -> 30
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationPicker(totalSeconds: Int, onChange: (Int) -> Unit, maxSeconds: Int) {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    fun set(nh: Int, nm: Int, ns: Int) = onChange((nh * 3600 + nm * 60 + ns).coerceIn(1, maxSeconds))

    SectionLabel("Duration")
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Stepper("h", h, 0..4, Modifier.weight(1f)) { set(it, m, s) }
        Stepper("min", m, 0..59, Modifier.weight(1f)) { set(h, it, s) }
        Stepper("sec", s, 0..59, Modifier.weight(1f)) { set(h, m, it) }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 30, 60, 120, 300, 600, 1500).forEach { q ->
            FilterChip(
                selected = totalSeconds == q,
                onClick = { onChange(q) },
                label = { Text(formatDuration(q * 1000L)) },
            )
        }
    }
}

@Composable
private fun Stepper(unit: String, value: Int, range: IntRange, modifier: Modifier, onChange: (Int) -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { onChange(if (value >= range.last) range.first else value + 1) }, modifier = Modifier.size(36.dp).testTag("up-$unit")) {
                Icon(RunletIcons.ExpandLess, contentDescription = "More $unit")
            }
            Text("%02d".format(value), style = RunletText.timerMedium, modifier = Modifier.testTag("value-$unit"))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { onChange(if (value <= range.first) range.last else value - 1) }, modifier = Modifier.size(36.dp).testTag("down-$unit")) {
                Icon(RunletIcons.ExpandMore, contentDescription = "Fewer $unit")
            }
        }
    }
}

@Composable
private fun VolumePicker(percent: Int, onChange: (Int) -> Unit) {
    SectionLabel("Media volume")
    Spacer(Modifier.height(4.dp))
    Text("$percent%", style = RunletText.timerMedium, modifier = Modifier.testTag("volume-value"))
    androidx.compose.material3.Slider(
        value = percent.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = 0f..100f, steps = 19,
        modifier = Modifier.semantics { contentDescription = "Media volume" }.testTag("volume-slider"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatSheet(count: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var n by remember { mutableStateOf(count) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).testTag("repeat-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(RunletIcons.Repeat, repeatTint())
                Spacer(Modifier.width(14.dp))
                Text("Repeat", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                FilledTonalButton(onClick = { n = (n - 1).coerceAtLeast(1) }, modifier = Modifier.testTag("repeat-minus")) {
                    Icon(RunletIcons.Remove, contentDescription = "One fewer")
                }
                Text(
                    "$n ${if (n == 1) "time" else "times"}",
                    style = RunletText.timerMedium,
                    modifier = Modifier.width(150.dp).padding(horizontal = 12.dp).testTag("repeat-count"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                FilledTonalButton(onClick = { n = (n + 1).coerceAtMost(100) }, modifier = Modifier.testTag("repeat-plus")) {
                    Icon(RunletIcons.Add, contentDescription = "One more")
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onSave(n) }, modifier = Modifier.testTag("repeat-save")) { Text("Done") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Advanced editor: syntax-highlighted script with live validation
// ------------------------------------------------------------------------------------------------

@Composable
private fun AdvancedEditor(
    text: String,
    validation: Validation,
    onChange: (String) -> Unit,
    contentPadding: PaddingValues,
    showFallbackNotice: Boolean,
) {
    val x = Runlet.extras
    val transformation = remember(x) { ScriptHighlighter(x.codeKeyword, x.codeString, x.codeNumber, x.codeComment, x.codeBrace) }
    Column(
        Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding() + 4.dp).padding(horizontal = 20.dp),
    ) {
        if (showFallbackNotice) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag("fallback-notice")) {
                Text(
                    "This script has an error, so the step view is unavailable. Fix it here and the steps will come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = RunletText.code.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
                modifier = Modifier.fillMaxSize().padding(16.dp).testTag("editor"),
            )
        }
        Spacer(Modifier.height(10.dp))
        ValidationBar(validation, Modifier.padding(bottom = contentPadding.calculateBottomPadding() + 12.dp))
    }
}

@Composable
private fun ValidationBar(validation: Validation, modifier: Modifier = Modifier) {
    when (validation) {
        is Validation.Ok -> Row(modifier.semantics(mergeDescendants = true) {}.testTag("validation"), verticalAlignment = Alignment.CenterVertically) {
            Icon(RunletIcons.CheckCircle, null, Modifier.size(18.dp), tint = Runlet.extras.success)
            Spacer(Modifier.width(8.dp))
            Text(
                "${stepsLabel(validation.steps)} · ${clockText(validation.totalMs)} · ${validation.alerts.keyword} alerts",
                style = MaterialTheme.typography.bodySmall,
                color = Runlet.extras.success,
            )
        }
        is Validation.Error -> Row(modifier.semantics(mergeDescendants = true) {}.testTag("validation"), verticalAlignment = Alignment.Top) {
            Icon(RunletIcons.Warning, null, Modifier.size(18.dp), tint = Runlet.extras.warning)
            Spacer(Modifier.width(8.dp))
            Text(validation.message, style = MaterialTheme.typography.bodySmall, color = Runlet.extras.warning)
        }
    }
}

/** Colours the routine language: keywords, quoted text, numbers/durations, comments and braces. */
private class ScriptHighlighter(
    private val keyword: androidx.compose.ui.graphics.Color,
    private val string: androidx.compose.ui.graphics.Color,
    private val number: androidx.compose.ui.graphics.Color,
    private val comment: androidx.compose.ui.graphics.Color,
    private val brace: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(highlight(text.text), OffsetMapping.Identity)

    private fun highlight(src: String): AnnotatedString = buildAnnotatedString {
        append(src)
        var lineStart = 0
        for (line in src.split('\n')) {
            val lead = line.length - line.trimStart().length
            val body = line.substring(lead)
            val at = lineStart + lead
            if (body.startsWith("#")) {
                addStyle(SpanStyle(color = comment), at, lineStart + line.length)
            } else {
                KEYWORD.find(body)?.let { addStyle(SpanStyle(color = keyword), at + it.range.first, at + it.range.last + 1) }
                for (q in QUOTED.findAll(body)) addStyle(SpanStyle(color = string), at + q.range.first, at + q.range.last + 1)
                for (n in NUMBER.findAll(body.replace(QUOTED, { " ".repeat(it.value.length) }))) {
                    addStyle(SpanStyle(color = number), at + n.range.first, at + n.range.last + 1)
                }
                for (i in body.indices) if (body[i] == '{' || body[i] == '}') addStyle(SpanStyle(color = brace), at + i, at + i + 1)
            }
            lineStart += line.length + 1
        }
    }

    private companion object {
        // Script constructs plus every registered action, so a new action type is coloured with no edit here.
        val KEYWORD = Regex(
            "^(" + (listOf("routine", "alerts", "when", "repeat") + ActionRegistry.builtIn.all().map { it.id }).joinToString("|") + ")\\b",
        )
        val QUOTED = Regex("\"[^\"\\r\\n]*\"")
        val NUMBER = Regex("\\b\\d+(ms|s|m|h)?\\b")
    }
}
