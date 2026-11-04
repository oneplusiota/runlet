package dev.runlet.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.runlet.app.MainViewModel
import dev.runlet.app.ui.editor.EditorContent
import dev.runlet.app.ui.guide.GuideScreen
import dev.runlet.app.ui.history.HistoryScreen
import dev.runlet.app.ui.home.HomeScreen
import dev.runlet.app.ui.home.TemplateSheet
import dev.runlet.app.ui.run.MiniRunBar
import dev.runlet.app.ui.run.RunScreen
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.app.ui.theme.RunletTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.runlet.core.engine.RunStatus

enum class Tab(val title: String) { Routines("Routines"), Activity("Activity"), Guide("Guide") }

/** Things only the Activity can do: permission prompts, file pickers, opening settings. */
interface HostActions {
    /** Requests what a run needs, then calls [onReady]. Not called if the user must act first. */
    fun ensureCanRun(onReady: () -> Unit)
    fun startRun(automationId: Long)
    /** Lets the user choose a `.routine` file or a `.zip` backup to import. */
    fun pickImport()
    /** Lets the user choose where to save one routine; [suggestedName] is the routine's name. */
    fun pickExport(suggestedName: String)
    /** Lets the user choose where to save a backup archive; [suggestedFileName] is the full file name. */
    fun pickBackup(suggestedFileName: String)
    /** Opens the system share sheet with the routine's text. */
    fun share(name: String, source: String)
    fun copyText(label: String, text: String)
    /** The clipboard's current text, or null when it holds none. */
    fun clipboardText(): String?
    fun openAppSettings()
    /** False on Android 12+ until the user allows exact alarms for Runlet. */
    fun canScheduleExactAlarms(): Boolean
    /** Opens the system screen where exact alarms are allowed. */
    fun openExactAlarmSettings()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunletApp(vm: MainViewModel, host: HostActions) {
    RunletTheme {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

        val routines by vm.routines.collectAsStateWithLifecycle()
        val runState by vm.runState.collectAsStateWithLifecycle()
        val history by vm.history.collectAsStateWithLifecycle()
        val openRunId by vm.openRunId.collectAsStateWithLifecycle()
        val openLogs by vm.openRunLogs.collectAsStateWithLifecycle()
        val selectedId by vm.selectedId.collectAsStateWithLifecycle()

        // Re-read when returning from the system setting, since the user changes it outside the app.
        var exactAllowed by remember { mutableStateOf(host.canScheduleExactAlarms()) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exactAllowed = host.canScheduleExactAlarms() }
        val exactAlarmMissing = !exactAllowed && routines.any { it.needsExactAlarm }

        var tab by rememberSaveable { mutableStateOf(Tab.Routines) }
        var showTemplates by rememberSaveable { mutableStateOf(false) }
        var showRun by rememberSaveable { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<Long?>(null) }
        var renaming by rememberSaveable { mutableStateOf(false) }

        val runActive = runState.status == RunStatus.RUNNING || runState.status == RunStatus.PAUSED
        val runVisible = showRun && (runActive || runState.status == RunStatus.COMPLETED || runState.status == RunStatus.ERROR)
        val editing = selectedId != null

        fun startRoutine(id: Long) =
            host.ensureCanRun { vm.run(id, host::startRun) { showRun = true } }

        BackHandler(enabled = runVisible) { showRun = false }
        BackHandler(enabled = editing && !runVisible) { vm.closeEditor() }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    if (editing) {
                        TopAppBar(
                            title = {
                                Text(
                                    vm.editorName(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("editor-title"),
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = vm::closeEditor, modifier = Modifier.testTag("editor-back")) {
                                    Icon(RunletIcons.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                IconButton(onClick = { renaming = true }, modifier = Modifier.testTag("rename")) {
                                    Icon(RunletIcons.Edit, contentDescription = "Rename")
                                }
                                IconButton(
                                    onClick = { selectedId?.let(::startRoutine) },
                                    modifier = Modifier.testTag("editor-run"),
                                ) { Icon(RunletIcons.Play, contentDescription = "Run", tint = MaterialTheme.colorScheme.primary) }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    }
                },
                bottomBar = {
                    Column {
                        AnimatedVisibility(
                            visible = runActive && !runVisible,
                            enter = slideInVertically { it } + fadeIn(),
                            exit = slideOutVertically { it } + fadeOut(),
                        ) {
                            MiniRunBar(
                                runState,
                                onOpen = { showRun = true },
                                onPauseResume = vm::pauseOrResume,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (editing) {
                            // Edge-to-edge: reserve the gesture-navigation inset, since the editor has no bar of its own.
                            Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
                        } else {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = t == tab,
                                        onClick = { tab = t },
                                        icon = {
                                            Icon(
                                                when (t) {
                                                    Tab.Routines -> RunletIcons.Home
                                                    Tab.Activity -> RunletIcons.History
                                                    Tab.Guide -> RunletIcons.Help
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        label = { Text(t.title) },
                                        modifier = Modifier.testTag("tab-${t.title}"),
                                    )
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize()) {
                    if (editing) {
                        Column(Modifier.fillMaxSize()) {
                            ModeSwitch(vm.advancedEditor, vm::useScriptEditor, Modifier.padding(top = padding.calculateTopPadding(), start = 20.dp, end = 20.dp, bottom = 4.dp))
                            if (vm.editorLoaded) {
                                EditorContent(
                                    outline = vm.outline,
                                    text = vm.editorText,
                                    validation = vm.validation,
                                    advanced = vm.advancedEditor,
                                    onTextChange = vm::onEdit,
                                    onEditOutline = vm::edit,
                                    onAlerts = vm::setAlerts,
                                    contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
                                )
                            }
                        }
                    } else {
                        when (tab) {
                            Tab.Routines -> HomeScreen(
                                routines = routines,
                                runningName = runState.automationName.takeIf { runActive },
                                onOpen = vm::openEditor,
                                onRun = ::startRoutine,
                                onNew = { showTemplates = true },
                                onImport = host::pickImport,
                                onPaste = { vm.importText(host.clipboardText()) },
                                onBackup = { host.pickBackup(vm.beginBackup()) },
                                onDuplicate = vm::duplicate,
                                onShare = { id -> vm.withRoutine(id) { name, source -> host.share(name, source) } },
                                onCopy = { id -> vm.withRoutine(id) { name, source -> host.copyText(name, source) } },
                                onExport = { id -> host.pickExport(vm.beginExport(id)) },
                                onDelete = { pendingDelete = it },
                                onToggleEnabled = vm::setEnabled,
                                exactAlarmMissing = exactAlarmMissing,
                                onAllowExactAlarms = host::openExactAlarmSettings,
                                contentPadding = padding,
                            )
                            Tab.Activity -> HistoryScreen(
                                runs = history,
                                openRunId = openRunId,
                                logs = openLogs,
                                onToggle = { id -> vm.openRun(if (id == openRunId) null else id) },
                                onClear = vm::clearHistory,
                                contentPadding = padding,
                            )
                            Tab.Guide -> GuideScreen(host::openAppSettings, padding)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = runVisible,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                RunScreen(
                    state = runState,
                    onPauseResume = vm::pauseOrResume,
                    onStop = { vm.stop(); showRun = false },
                    onClose = { showRun = false; if (!runActive) vm.stop() },
                )
            }
        }

        if (showTemplates) {
            TemplateSheet(
                onDismiss = { showTemplates = false },
                onPick = { template ->
                    showTemplates = false
                    vm.createFromTemplate(template) { id -> vm.openEditor(id) }
                },
            )
        }

        pendingDelete?.let { id ->
            val name = routines.firstOrNull { it.id == id }?.name ?: "this routine"
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete “$name”?") },
                text = { Text("The routine is removed. Its run history is kept.") },
                confirmButton = {
                    TextButton(onClick = { pendingDelete = null; vm.delete(id) }, modifier = Modifier.testTag("confirm-delete")) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            )
        }

        if (renaming && editing) {
            var name by remember { mutableStateOf(vm.editorName()) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Rename routine") },
                text = {
                    OutlinedTextField(
                        name, { name = it }, singleLine = true, label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth().testTag("rename-field"),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { if (vm.rename(name)) renaming = false }, modifier = Modifier.testTag("rename-confirm")) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(advanced: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !advanced,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            modifier = Modifier.testTag("mode-steps"),
            icon = {},
        ) { Text("Steps") }
        SegmentedButton(
            selected = advanced,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            modifier = Modifier.testTag("mode-script"),
            icon = {},
        ) { Text("Script") }
    }
}
