package dev.runlet.core.platform

import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.data.RunHistoryRepository
import dev.runlet.core.engine.ActionRegistry
import dev.runlet.core.engine.AutomationRunner
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.Platform
import dev.runlet.core.engine.RunState
import dev.runlet.core.engine.RunStatus
import dev.runlet.core.engine.ScriptParser
import dev.runlet.core.model.Automation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the [AutomationRunner] and mirrors it into run history. It has no Android
 * dependencies beyond its inputs, so the foreground service stays a thin host.
 *
 * History writes go through one ordered queue, because Room's suspend calls may run on
 * different threads and log lines must land in the order they were produced.
 * History is best-effort: a failed write never stops a run.
 */
class RunCoordinator(
    scope: CoroutineScope,
    registry: ActionRegistry,
    private val platform: Platform,
    private val history: RunHistoryRepository,
    private val automations: AutomationRepository,
    clock: Clock,
) {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** (runner runNumber, history row id) of the run still open in history. Touched only from [queue]. */
    private var open: Pair<Int, Long>? = null

    private val runner = AutomationRunner(registry, RecordingPlatform(platform), clock, scope)
    val state: StateFlow<RunState> = runner.state

    init {
        scope.launch {
            for (op in queue) {
                try {
                    op()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // best-effort history
                }
            }
        }
        // A fresh process cannot have a live run, so rows still marked active were killed with the old one.
        enqueue { history.closeOrphanedRuns() }
        scope.launch {
            runner.state.map { it.runNumber to it.status }.distinctUntilChanged().collect { (number, status) ->
                val error = runner.state.value.error
                when (status) {
                    RunStatus.RUNNING -> enqueue { setStatusIfOpen(number, "RUNNING") }
                    RunStatus.PAUSED -> enqueue { setStatusIfOpen(number, "PAUSED") }
                    RunStatus.COMPLETED -> enqueue { finishIfOpen(number, "COMPLETED") }
                    RunStatus.ERROR -> enqueue { finishIfOpen(number, "ERROR", error) }
                    RunStatus.IDLE -> Unit
                }
            }
        }
    }

    /** Loads a saved automation and runs it. Throws if it is missing or no longer parses. */
    suspend fun startSaved(automationId: Long) {
        val row = automations.get(automationId) ?: throw IllegalArgumentException("Automation no longer exists")
        start(ScriptParser.parse(row.source), automationId)
    }

    /** Runs [automation], replacing any run in progress. */
    fun start(automation: Automation, automationId: Long? = null) {
        // The history row is queued before the run starts, so the run's first log lines
        // (queued behind it) always find their row, even on an immediate dispatcher.
        runner.start(automation) { number ->
            enqueue {
                open?.let { (_, id) -> history.finish(id, "STOPPED") } // replaced before finishing
                open = number to history.begin(automationId, automation.name)
            }
        }
    }

    /** True while a run is in progress, paused or not. */
    val isActive: Boolean
        get() = runner.state.value.status.let { it == RunStatus.RUNNING || it == RunStatus.PAUSED }

    /** Notes in run history that [automationId] was due but did not start, so a missing run is explainable. */
    fun recordSkipped(automationId: Long, reason: String) = enqueue {
        val name = automations.get(automationId)?.name ?: return@enqueue
        history.recordSkipped(automationId, name, reason)
    }

    /** Tells the user a run could not even begin (missing or invalid automation). */
    fun reportFailure(message: String) = platform.notify("Routine stopped", message)

    fun pause() = runner.pause()

    fun resume() = runner.resume()

    fun stop() {
        runner.stop()
        enqueue { open?.let { (number, _) -> finishIfOpen(number, "STOPPED") } }
    }

    private suspend fun setStatusIfOpen(number: Int, status: String) {
        open?.takeIf { it.first == number }?.let { history.setStatus(it.second, status) }
    }

    private suspend fun finishIfOpen(number: Int, status: String, error: String? = null) {
        val current = open?.takeIf { it.first == number } ?: return
        history.finish(current.second, status, error)
        open = null
    }

    private fun enqueue(op: suspend () -> Unit) {
        queue.trySend(op)
    }

    /** Adds every log line the engine emits to the open run's history. */
    private inner class RecordingPlatform(private val delegate: Platform) : Platform by delegate {
        override fun log(message: String) {
            delegate.log(message)
            enqueue { open?.let { history.log(it.second, message) } }
        }
    }
}
