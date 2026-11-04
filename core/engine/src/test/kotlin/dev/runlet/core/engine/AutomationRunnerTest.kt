package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Automation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakePlatform : Platform {
    val events = mutableListOf<String>()
    override fun chime() { events += "chime" }
    override fun vibrate(durationMs: Long) { events += "vibrate:$durationMs" }
    override fun notify(title: String, message: String) { events += "notify:$title|$message" }
    override fun setMediaVolume(percent: Int) { events += "volume:$percent" }
    override fun log(message: String) { events += "log:$message" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationRunnerTest {
    private fun TestScope.runner(platform: Platform, registry: ActionRegistry = ActionRegistry.default()) =
        AutomationRunner(registry, platform, Clock { currentTime }, this)

    @Test fun runsActionsInOrderAndCompletes() = runTest {
        val p = FakePlatform()
        val r = runner(p)
        r.start(Automation("Demo", AlertMode.NONE, listOf(
            Action.Log("hi"), Action.Wait(1_000), Action.Volume(30), Action.Notify("done"),
        )))
        advanceUntilIdle()
        assertEquals(RunStatus.COMPLETED, r.state.value.status)
        assertEquals(1_000, currentTime)
        val order = p.events.filter { it.startsWith("log:hi") || it.startsWith("volume") || it.startsWith("notify:Demo|done") }
        assertEquals(listOf("log:hi", "volume:30", "notify:Demo|done"), order)
    }

    @Test fun stageAlertsOnEntryPerMode() = runTest {
        val p = FakePlatform()
        val r = runner(p)
        r.start(Automation("Both", AlertMode.BOTH, listOf(Action.Stage("A", 500))))
        runCurrent()
        assertTrue("chime" in p.events && "vibrate:180" in p.events)
        advanceUntilIdle()

        val q = FakePlatform()
        runner(q).start(Automation("Quiet", AlertMode.NONE, listOf(Action.Stage("A", 500))))
        advanceUntilIdle()
        assertTrue(q.events.none { it == "chime" || it.startsWith("vibrate") })
    }

    @Test fun pauseFreezesTimerAndResumeFinishesRemainder() = runTest {
        val r = runner(FakePlatform())
        r.start(Automation("T", AlertMode.NONE, listOf(Action.Wait(10_000))))
        advanceTimeBy(4_000); runCurrent()
        r.pause()
        assertEquals(RunStatus.PAUSED, r.state.value.status)
        assertEquals(6_000, r.state.value.remainingMs)
        advanceTimeBy(60_000); runCurrent()          // long pause must not consume the timer
        assertEquals(RunStatus.PAUSED, r.state.value.status)
        r.resume()
        val resumedAt = currentTime
        advanceTimeBy(5_999); runCurrent()
        assertEquals(RunStatus.RUNNING, r.state.value.status)
        advanceTimeBy(1); runCurrent()
        assertEquals(RunStatus.COMPLETED, r.state.value.status)
        assertEquals(6_000, currentTime - resumedAt)
    }

    @Test fun stopCancelsAndReturnsToIdle() = runTest {
        val p = FakePlatform()
        val r = runner(p)
        r.start(Automation("T", AlertMode.NONE, listOf(Action.Wait(10_000), Action.Notify("never"))))
        advanceTimeBy(1_000); runCurrent()
        r.stop()
        advanceUntilIdle()
        assertEquals(RunStatus.IDLE, r.state.value.status)
        assertTrue(p.events.none { it.startsWith("notify:T|never") })
    }

    @Test fun startingAgainReplacesTheRunInProgress() = runTest {
        val r = runner(FakePlatform())
        r.start(Automation("First", AlertMode.NONE, listOf(Action.Wait(10_000))))
        advanceTimeBy(1_000); runCurrent()
        r.start(Automation("Second", AlertMode.NONE, listOf(Action.Wait(2_000))))
        advanceUntilIdle()
        assertEquals("Second", r.state.value.automationName)
        assertEquals(RunStatus.COMPLETED, r.state.value.status)
        assertEquals(3_000, currentTime)
    }

    @Test fun actionFailureStopsRunAndReportsError() = runTest {
        val failing = object : Platform by FakePlatform() {
            override fun setMediaVolume(percent: Int) = throw IllegalStateException("no audio service")
        }
        val r = runner(failing)
        r.start(Automation("T", AlertMode.NONE, listOf(Action.Volume(10), Action.Wait(1_000))))
        advanceUntilIdle()
        assertEquals(RunStatus.ERROR, r.state.value.status)
        assertEquals("no audio service", r.state.value.error)
        assertEquals(0, currentTime)                 // the wait after the failure never ran
    }

    @Test fun registryReportsCapabilitiesAndSummaries() {
        val reg = ActionRegistry.default()
        val a = Automation("T", AlertMode.SOUND, listOf(Action.Stage("Bloom", 30_000), Action.Volume(40), Action.Notify("x")))
        assertEquals(
            setOf(Capability.AUDIO_SETTINGS, Capability.NOTIFICATIONS, Capability.SOUND),
            reg.requiredCapabilities(a),
        )
        assertEquals("Stage “Bloom” for 30 s", reg.summary(a.actions[0]))
        assertEquals(7, reg.all().size)
    }
}
