package dev.runlet.core.platform

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.data.RunHistoryRepository
import dev.runlet.core.data.RunletDatabase
import dev.runlet.core.engine.ActionRegistry
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.Platform
import dev.runlet.core.engine.RunStatus
import dev.runlet.core.engine.ScriptParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingPlatform : Platform {
    val notifications = mutableListOf<String>()
    override fun chime() {}
    override fun vibrate(durationMs: Long) {}
    override fun notify(title: String, message: String) { notifications += "$title|$message" }
    override fun setMediaVolume(percent: Int) {}
    override fun log(message: String) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RunCoordinatorTest {
    private lateinit var db: RunletDatabase
    private lateinit var history: RunHistoryRepository
    private lateinit var automations: AutomationRepository
    private val platform = RecordingPlatform()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RunletDatabase::class.java)
            .allowMainThreadQueries()
            // Run queries inline: with real I/O threads, runTest sees an idle scheduler while the
            // body waits and fast-forwards virtual time, finishing runs the test means to stop.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After fun tearDown() = db.close()

    private fun TestScope.coordinator(): RunCoordinator = newCoordinator().also { coord = it }

    private fun TestScope.newCoordinator(): RunCoordinator {
        val clock = Clock { currentTime }
        history = RunHistoryRepository(db.runs(), clock)
        automations = AutomationRepository(db.automations(), clock)
        // backgroundScope is cancelled when the test ends, so the coordinator's endless queue consumer can't hang runTest.
        return RunCoordinator(backgroundScope, ActionRegistry.default(), platform, history, automations, clock)
    }

    /**
     * Polls until [condition] holds. History writes are real Room I/O on other threads whose
     * continuations resume on the test dispatcher, so we alternate scheduler turns with short real
     * sleeps, and fail with [what] if it never happens (instead of guessing a fixed delay).
     */
    private suspend fun TestScope.await(what: String, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            runCurrent() // not advanceUntilIdle: that would also fast-forward the run's virtual wait
            if (condition()) return
            Thread.sleep(5)
        }
        throw AssertionError("Timed out waiting for: $what; history=${history.observeRecent().first()} runner=${coord?.state?.value} logs=${history.observeRecent().first().firstOrNull()?.let { history.observeLogs(it.id).first().map { l -> l.message } }}")
    }

    private var coord: RunCoordinator? = null

    private suspend fun statuses() = history.observeRecent().first().map { it.status }

    @Test fun completedRunIsRecordedWithOrderedLogs() = runTest(StandardTestDispatcher()) {
        val c = coordinator()
        val id = automations.save(null, "routine \"T\"\nalerts none\nlog \"one\"\nwait 2s\nlog \"two\"")
        c.startSaved(id)
        // advanceUntilIdle() ignores backgroundScope work, so move virtual time explicitly past the 2s wait.
        advanceTimeBy(2_001); runCurrent()
        await("run COMPLETED") { statuses() == listOf("COMPLETED") }
        await("all logs written") { history.observeLogs(history.observeRecent().first().single().id).first().size == 5 }

        val run = history.observeRecent().first().single()
        assertEquals("COMPLETED", run.status)
        assertEquals(id, run.automationId)
        val lines = history.observeLogs(run.id).first().map { it.message }
        assertEquals(listOf("Started: T", "one", "Wait · 00:02", "two", "Completed: T"), lines)
    }

    /** Regression: on an eager dispatcher (the app's Main.immediate) the first log lines used to be lost. */
    @Test fun firstLogLinesAreKeptWhenTheRunStartsEagerly() = runTest(StandardTestDispatcher()) {
        val clock = Clock { currentTime }
        history = RunHistoryRepository(db.runs(), clock)
        automations = AutomationRepository(db.automations(), clock)
        val eager = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob())
        try {
            val c = RunCoordinator(eager, ActionRegistry.default(), platform, history, automations, clock)
            val id = automations.save(null, "routine \"T\"\nalerts none\nlog \"one\"\nlog \"two\"")
            c.startSaved(id)
            await("run COMPLETED") { statuses() == listOf("COMPLETED") }
            await("all logs written") { history.observeLogs(history.observeRecent().first().single().id).first().size == 4 }
            val lines = history.observeLogs(history.observeRecent().first().single().id).first().map { it.message }
            assertEquals(listOf("Started: T", "one", "two", "Completed: T"), lines)
        } finally {
            eager.cancel()
        }
    }

    @Test fun stopMarksRunStopped() = runTest(StandardTestDispatcher()) {
        val c = coordinator()
        val id = automations.save(null, "routine \"T\"\nwait 10s")
        c.startSaved(id)
        advanceTimeBy(3_000); runCurrent()
        await("run recorded as RUNNING") { statuses() == listOf("RUNNING") }
        c.stop()
        await("run STOPPED") { statuses() == listOf("STOPPED") }
        assertEquals("STOPPED", history.observeRecent().first().single().status)
        assertEquals(RunStatus.IDLE, c.state.value.status)
    }

    @Test fun missingAutomationFailsWithoutAHistoryRow() = runTest(StandardTestDispatcher()) {
        val c = coordinator()
        val e = runCatching { c.startSaved(999) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        c.reportFailure("gone")
        assertEquals(listOf("Routine stopped|gone"), platform.notifications)
        repeat(20) { advanceUntilIdle(); Thread.sleep(5) } // nothing to wait for: assert that nothing appears
        assertTrue(history.observeRecent().first().isEmpty())
    }

    @Test fun orphanedRunsFromAKilledProcessAreClosedAtStartup() = runTest(StandardTestDispatcher()) {
        val clock = Clock { 0 }
        RunHistoryRepository(db.runs(), clock).begin(null, "killed")
        val c = coordinator()
        await("orphan closed") { statuses() == listOf("STOPPED") }
        assertEquals(RunStatus.IDLE, c.state.value.status)
    }
}
