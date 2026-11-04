package dev.runlet.app

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.runlet.core.data.AutomationEntity
import dev.runlet.core.data.AutomationSaveException
import dev.runlet.core.data.RunEntity
import dev.runlet.core.data.RunLogEntity
import dev.runlet.core.engine.Outline
import dev.runlet.core.engine.RoutineBundle
import dev.runlet.core.engine.RoutineFile
import dev.runlet.core.engine.RoutineTemplate
import dev.runlet.core.engine.RunState
import dev.runlet.core.engine.ScriptParser
import dev.runlet.core.engine.ScriptPrinter
import dev.runlet.core.engine.ScriptText
import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Trigger
import dev.runlet.core.platform.RunletServices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Result of checking a script. */
sealed interface Validation {
    data class Ok(val steps: Int, val totalMs: Long, val alerts: AlertMode) : Validation
    data class Error(val message: String) : Validation
}

/** What the routine list needs to know about one saved script. [error] is set when it cannot run. */
data class RoutineSummary(
    val id: Long,
    val name: String,
    val steps: Int,
    val totalMs: Long,
    val stages: List<String>,
    val error: String?,
    val enabled: Boolean = false,
    /** Plain-English `when` triggers, empty when the routine is manual only. */
    val triggers: List<String> = emptyList(),
    /** True when it is armed and has a clock-time trigger, which is late without the exact-alarm permission. */
    val needsExactAlarm: Boolean = false,
)

/** Small UI settings that survive restarts. */
interface UiPrefs {
    var advancedEditor: Boolean
    /** Set once the first-launch routines have been created, so deleting them all doesn't bring them back. */
    var seeded: Boolean

    class InMemory(override var advancedEditor: Boolean = false, override var seeded: Boolean = false) : UiPrefs
}

class MainViewModel(
    services: RunletServices.Graph,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val autosaveDelayMs: Long = AUTOSAVE_DELAY_MS,
    private val prefs: UiPrefs = UiPrefs.InMemory(),
) : ViewModel() {
    private val automationsRepo = services.automations
    private val historyRepo = services.history
    private val coordinator = services.coordinator

    /** Outlives the ViewModel so the last edits are still written when the screen is torn down. */
    private val saveScope = CoroutineScope(SupervisorJob() + io)

    val routines: StateFlow<List<RoutineSummary>> = automationsRepo.observeAll()
        .map { list -> list.map(::summarize) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val runState: StateFlow<RunState> = coordinator.state

    val history: StateFlow<List<RunEntity>> =
        historyRepo.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Editor ------------------------------------------------------------------------------

    private val _selectedId = MutableStateFlow<Long?>(null)
    val selectedId: StateFlow<Long?> = _selectedId.asStateFlow()

    /** True once the selected routine's text has been loaded into the editor. */
    var editorLoaded by mutableStateOf(false)
        private set

    /** The editor's script text. Both editors read and write this; it is the single source of truth. */
    var editorText by mutableStateOf("")
        private set

    /** The script as the user wrote it, or null while it has a syntax error. Drives the Basic editor. */
    var outline by mutableStateOf<Outline?>(null)
        private set

    var validation by mutableStateOf<Validation>(Validation.Error(""))
        private set

    var advancedEditor by mutableStateOf(prefs.advancedEditor)
        private set

    private var autosave: Job? = null
    private var loadedId: Long? = null

    // --- Run history ---------------------------------------------------------------------------

    private val _openRunId = MutableStateFlow<Long?>(null)
    val openRunId: StateFlow<Long?> = _openRunId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val openRunLogs: StateFlow<List<RunLogEntity>> = _openRunId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else historyRepo.observeLogs(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-shot user messages (errors, confirmations). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            if (!prefs.seeded) {
                if (automationsRepo.observeAll().first().isEmpty()) {
                    // The three routines the prototype shipped with. Yoga is intentionally not runnable yet.
                    automationsRepo.importSource(ScriptParser.COFFEE)
                    automationsRepo.importSource(ScriptParser.DEMO)
                    automationsRepo.importSource(ScriptParser.YOGA)
                }
                prefs.seeded = true
            }
        }
    }

    // --- Opening, editing, saving --------------------------------------------------------------

    fun openEditor(id: Long) {
        viewModelScope.launch {
            flushNow()
            editorLoaded = false
            _selectedId.value = id
            val row = automationsRepo.get(id)
            loadedId = row?.id
            setText(row?.source.orEmpty())
            editorLoaded = row != null
        }
    }

    /** Saves pending edits and leaves the editor. */
    fun closeEditor() {
        saveScope.launch {
            flushNow()
            withContext(Dispatchers.Main.immediate) {
                loadedId = null
                _selectedId.value = null
                editorLoaded = false
            }
        }
    }

    fun onEdit(text: String) {
        if (text == editorText) return
        setText(text)
        val id = loadedId ?: return
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(autosaveDelayMs)
            persist(id, text)
        }
    }

    /** Applies [transform] to the structured script and prints it back. Used by the Basic editor. */
    fun edit(transform: (Outline) -> Outline) {
        val current = outline ?: return
        onEdit(transform(current).print())
    }

    fun setAlerts(mode: AlertMode) = edit { it.copy(alerts = mode) }

    fun useScriptEditor(advanced: Boolean) {
        advancedEditor = advanced
        prefs.advancedEditor = advanced
    }

    /** Renames the open routine by rewriting its `routine` line. Returns false (with a message) if the name is unusable. */
    fun rename(name: String): Boolean {
        val clean = name.trim()
        if (!ScriptText.isValidName(clean)) {
            _messages.tryEmit("Use a name of 1–80 characters without quotes")
            return false
        }
        val id = loadedId ?: return false
        if (routines.value.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) {
            _messages.tryEmit("A routine named “$clean” already exists")
            return false
        }
        onEdit(ScriptText.renameRoutine(editorText, clean))
        return true
    }

    fun editorName(): String =
        ScriptText.routineName(editorText) ?: routines.value.firstOrNull { it.id == _selectedId.value }?.name ?: "Routine"

    /** Saves pending edits immediately. Call when the screen leaves the foreground. */
    fun flush() {
        saveScope.launch { flushNow() }
    }

    private suspend fun flushNow() {
        autosave?.cancel()
        autosave = null
        loadedId?.let { persist(it, editorText) }
    }

    private suspend fun persist(id: Long, text: String) {
        try {
            automationsRepo.saveDraft(id, text)
        } catch (e: AutomationSaveException) {
            _messages.tryEmit(e.message ?: "Could not save")
        }
    }

    private fun setText(text: String) {
        editorText = text
        outline = try {
            Outline.parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }
        validation = validate(text)
    }

    // --- Routines ------------------------------------------------------------------------------

    fun createFromTemplate(template: RoutineTemplate, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = automationsRepo.importSource(template.source)
            onCreated(id)
        }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch {
            if (id == loadedId) flushNow()
            val row = automationsRepo.get(id) ?: return@launch
            automationsRepo.importSource(row.source)
            _messages.tryEmit("Duplicated “${row.name}”")
        }
    }

    /** Arms or disarms a routine's `when` triggers. The scheduler re-arms itself when the list changes. */
    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { automationsRepo.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            if (id == loadedId) {
                autosave?.cancel()
                loadedId = null
                _selectedId.value = null
                editorLoaded = false
            }
            automationsRepo.delete(id)
        }
    }

    // --- Running -------------------------------------------------------------------------------

    /** Starts routine [id] after saving it and checking it still parses. [onStarted] runs once the start was requested. */
    fun run(id: Long, startService: (Long) -> Unit, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            if (id == loadedId) flushNow()
            val row = automationsRepo.get(id)
            if (row == null) {
                _messages.tryEmit("That routine no longer exists")
                return@launch
            }
            when (val v = validate(row.source)) {
                is Validation.Error -> _messages.tryEmit("Script needs editing: ${v.message}")
                is Validation.Ok -> {
                    startService(id)
                    onStarted()
                }
            }
        }
    }

    fun pauseOrResume() {
        if (runState.value.status == dev.runlet.core.engine.RunStatus.PAUSED) coordinator.resume() else coordinator.pause()
    }

    fun stop() = coordinator.stop()

    // --- History -------------------------------------------------------------------------------

    fun openRun(id: Long?) {
        _openRunId.value = id
    }

    fun clearHistory() {
        viewModelScope.launch {
            _openRunId.value = null
            historyRepo.clear()
        }
    }

    // --- Import / export -----------------------------------------------------------------------

    /** What the next file the user picks should receive. Held here because the picker returns to the Activity. */
    private sealed interface PendingExport {
        data class One(val id: Long) : PendingExport
        data object Everything : PendingExport
    }

    private var pendingExport: PendingExport? = null

    /**
     * Imports whatever [uri] holds: one `.routine` text or a `.zip` of them. Every routine becomes a draft (even if
     * it does not parse yet) and none is run. A single routine opens in the editor so it can be reviewed.
     */
    fun importFrom(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                val contents = withContext(io) {
                    val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                    input.use { RoutineBundle.read(it) }
                }
                finishImport(contents.files.map { it.source }, contents.skipped)
            } catch (e: IOException) {
                _messages.tryEmit("Import failed: ${e.message}")
            } catch (e: SecurityException) {
                _messages.tryEmit("Import failed: Runlet is not allowed to read that file")
            }
        }
    }

    /** Imports pasted or shared text, but only when it looks like a routine. */
    fun importText(text: String?) {
        if (!RoutineBundle.looksLikeRoutine(text)) {
            _messages.tryEmit(
                if (text.isNullOrBlank()) "There is nothing to paste" else "That text doesn't look like a routine",
            )
            return
        }
        viewModelScope.launch { finishImport(listOf(text!!), skipped = 0) }
    }

    private suspend fun finishImport(sources: List<String>, skipped: Int) {
        if (sources.isEmpty()) {
            _messages.tryEmit("No routines were found in that file")
            return
        }
        flushNow()
        val ids = sources.map { automationsRepo.importSource(it) }
        val extra = if (skipped > 0) ", skipped $skipped that ${if (skipped == 1) "was" else "were"}n't text" else ""
        if (ids.size == 1) {
            val name = automationsRepo.get(ids.single())?.name.orEmpty()
            openEditor(ids.single())
            _messages.tryEmit("Imported “$name”; review before running$extra")
        } else {
            _messages.tryEmit("Imported ${ids.size} routines; review before running$extra")
        }
    }

    /** Remembers that the next [exportTo] should write routine [id]. Returns the routine's name for the file picker. */
    fun beginExport(id: Long): String {
        pendingExport = PendingExport.One(id)
        return routines.value.firstOrNull { it.id == id }?.name ?: "routine"
    }

    /** Remembers that the next [exportTo] should write every routine as an archive. Returns the suggested file name. */
    fun beginBackup(): String {
        pendingExport = PendingExport.Everything
        return "Runlet backup ${LocalDate.now()}.zip"
    }

    fun exportTo(resolver: ContentResolver, uri: Uri) {
        val pending = pendingExport
        pendingExport = null
        if (pending == null) {
            _messages.tryEmit("The export was interrupted. Please try again.")
            return
        }
        viewModelScope.launch {
            flushNow()
            val (bytes, done) = when (pending) {
                is PendingExport.One -> {
                    val row = automationsRepo.get(pending.id)
                    if (row == null) {
                        _messages.tryEmit("That routine no longer exists")
                        return@launch
                    }
                    row.source.toByteArray(Charsets.UTF_8) to "Exported “${row.name}”"
                }
                PendingExport.Everything -> {
                    val all = automationsRepo.observeAll().first()
                    if (all.isEmpty()) {
                        _messages.tryEmit("There are no routines to back up")
                        return@launch
                    }
                    RoutineBundle.writeZip(all.map { RoutineFile(it.name, it.source) }) to
                        "Backed up ${all.size} ${if (all.size == 1) "routine" else "routines"}"
                }
            }
            try {
                withContext(io) {
                    val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the file")
                    out.use { it.write(bytes) }
                }
                _messages.tryEmit(done)
            } catch (e: IOException) {
                _messages.tryEmit("Export failed: ${e.message}")
            }
        }
    }

    /** Hands routine [id]'s name and current text to [use], after saving any pending edit. For share and copy. */
    fun withRoutine(id: Long, use: (name: String, source: String) -> Unit) {
        viewModelScope.launch {
            if (id == loadedId) flushNow()
            val row = automationsRepo.get(id)
            if (row == null) _messages.tryEmit("That routine no longer exists") else use(row.name, row.source)
        }
    }

    /** Shows [message] to the user (used by the Activity for permission problems). */
    fun report(message: String) {
        _messages.tryEmit(message)
    }

    override fun onCleared() {
        // viewModelScope is already cancelled, so hand any unsaved edit to the scope that outlives us.
        val id = loadedId
        if (id != null && autosave?.isActive == true) {
            val text = editorText
            saveScope.launch { runCatching { automationsRepo.saveDraft(id, text) } }
        }
    }

    companion object {
        const val AUTOSAVE_DELAY_MS = 500L

        fun validate(text: String): Validation = try {
            val a = ScriptParser.parse(text)
            Validation.Ok(a.actions.size, a.totalMs, a.alerts)
        } catch (e: IllegalArgumentException) {
            Validation.Error(e.message ?: "Invalid script")
        }

        internal fun summarize(e: AutomationEntity): RoutineSummary = try {
            val a = ScriptParser.parse(e.source)
            RoutineSummary(
                id = e.id,
                name = e.name,
                steps = a.actions.size,
                totalMs = a.totalMs,
                stages = a.actions.filterIsInstance<Action.Stage>().map { it.label }.distinct().take(4),
                error = null,
                enabled = e.enabled,
                triggers = a.triggers.map(ScriptPrinter::describe),
                needsExactAlarm = e.enabled && a.triggers.any { it is Trigger.Time },
            )
        } catch (ex: IllegalArgumentException) {
            RoutineSummary(e.id, e.name, 0, 0, emptyList(), ex.message ?: "Invalid script", e.enabled)
        }

        fun factory(graph: RunletServices.Graph, prefs: UiPrefs) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(graph, prefs = prefs) as T
        }
    }
}
