package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Automation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class RunStatus { IDLE, RUNNING, PAUSED, COMPLETED, ERROR }

/**
 * Snapshot for UI and notifications.
 * While RUNNING, the current wait ends at [deadlineMs] (per the runner's [Clock]);
 * while PAUSED, [remainingMs] holds the time left.
 */
data class RunState(
    val status: RunStatus = RunStatus.IDLE,
    val automationName: String = "",
    val label: String = "Ready",
    /** Zero-based index of the running action, or -1 before the first. */
    val actionIndex: Int = -1,
    val actionCount: Int = 0,
    val deadlineMs: Long = 0,
    val remainingMs: Long = 0,
    val error: String? = null,
    /** Increments on every [AutomationRunner.start]; lets observers tell runs apart. */
    val runNumber: Int = 0,
    /** Full length of the step that is currently timing (0 when none is). */
    val stepTotalMs: Long = 0,
    /** Scheduled time of all steps that have already finished. */
    val elapsedBeforeStepMs: Long = 0,
    /** Scheduled time of the whole run. */
    val totalMs: Long = 0,
    /** Label of the next stage, if any, for "Up next". */
    val nextLabel: String? = null,
)

/**
 * Runs one automation at a time on [scope]. All control methods are safe to call from any
 * thread; state is published through [state]. Replaces the prototype's Handler tick chain
 * with suspending code, so pause/resume and cancellation are just suspension and cancel.
 */
class AutomationRunner(
    private val registry: ActionRegistry,
    private val platform: Platform,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var job: Job? = null
    private val lock = Any()

    /**
     * Starts [automation], replacing any run in progress. [onBegin] is called with the new run
     * number before the first action executes: on an immediate dispatcher the run starts (and
     * logs) inside this call, so anything that must see the run first has to be set up here.
     */
    fun start(automation: Automation, onBegin: (runNumber: Int) -> Unit = {}): Int = synchronized(lock) {
        job?.cancel()
        paused.value = false
        val number = _state.value.runNumber + 1
        _state.value = RunState(
            status = RunStatus.RUNNING,
            automationName = automation.name,
            label = "Starting",
            actionCount = automation.actions.size,
            runNumber = number,
            totalMs = automation.totalMs,
            nextLabel = nextStageLabel(automation, -1),
        )
        onBegin(number)
        job = scope.launch { run(automation) }
        number
    }

    fun pause() = synchronized(lock) {
        if (_state.value.status != RunStatus.RUNNING) return@synchronized
        val remaining = (_state.value.deadlineMs - clock.nowMs()).coerceAtLeast(0)
        paused.value = true
        _state.update { it.copy(status = RunStatus.PAUSED, remainingMs = remaining) }
        platform.log("Paused")
    }

    fun resume() = synchronized(lock) {
        if (_state.value.status != RunStatus.PAUSED) return@synchronized
        _state.update {
            it.copy(status = RunStatus.RUNNING, deadlineMs = clock.nowMs() + it.remainingMs)
        }
        paused.value = false
        platform.log("Resumed")
    }

    /** Stops the current run and returns to idle. */
    fun stop() = synchronized(lock) {
        val wasActive = _state.value.status.let { it == RunStatus.RUNNING || it == RunStatus.PAUSED }
        job?.cancel()
        job = null
        paused.value = false
        if (wasActive) platform.log("Stopped and reset")
        _state.value = RunState(runNumber = _state.value.runNumber)
    }

    private suspend fun run(automation: Automation) {
        val ctx = Context(automation)
        try {
            platform.log("Started: ${automation.name}")
            var elapsed = 0L
            automation.actions.forEachIndexed { index, action ->
                _state.update {
                    it.copy(
                        actionIndex = index,
                        elapsedBeforeStepMs = elapsed,
                        stepTotalMs = 0,
                        nextLabel = nextStageLabel(automation, index),
                    )
                }
                registry.execute(action, ctx)
                elapsed += action.scheduledMs
            }
            _state.update {
                it.copy(
                    status = RunStatus.COMPLETED, label = "Complete", remainingMs = 0, deadlineMs = 0,
                    stepTotalMs = 0, elapsedBeforeStepMs = automation.totalMs, nextLabel = null,
                )
            }
            ctx.alert()
            platform.log("Completed: ${automation.name}")
            platform.notify("${automation.name} complete", "All steps finished.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName ?: "Unknown error"
            platform.log("Error: $message")
            _state.update { it.copy(status = RunStatus.ERROR, label = message, error = message) }
            platform.notify("Routine stopped", message)
        }
    }

    private inner class Context(override val automation: Automation) : ExecutionContext {
        override val platform: Platform get() = this@AutomationRunner.platform

        override fun setLabel(label: String) = _state.update { it.copy(label = label) }

        override fun alert() {
            if (automation.alerts == AlertMode.BOTH || automation.alerts == AlertMode.SOUND) platform.chime()
            if (automation.alerts == AlertMode.BOTH || automation.alerts == AlertMode.VIBRATION) {
                platform.vibrate(ENTRY_VIBRATION_MS)
            }
        }

        override suspend fun sleep(ms: Long) {
            var remaining = ms
            _state.update { it.copy(remainingMs = remaining, deadlineMs = clock.nowMs() + remaining, stepTotalMs = ms) }
            while (remaining > 0) {
                paused.first { !it }
                val startedAt = clock.nowMs()
                val interrupted = withTimeoutOrNull(remaining) { paused.first { it } } != null
                if (interrupted) remaining -= clock.nowMs() - startedAt else remaining = 0
            }
            _state.update { it.copy(remainingMs = 0) }
        }
    }

    private companion object {
        const val ENTRY_VIBRATION_MS = 180L

        /** Label of the first stage after [index], or null when none remains. */
        fun nextStageLabel(automation: Automation, index: Int): String? =
            automation.actions.drop(index + 1).filterIsInstance<Action.Stage>().firstOrNull()?.label
    }
}
