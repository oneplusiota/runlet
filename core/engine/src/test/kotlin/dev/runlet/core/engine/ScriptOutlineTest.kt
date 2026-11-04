package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ScriptOutlineTest {
    private val sample = """
        # Warm up
        routine "Workout"
        alerts sound
        stage "Ready" 30s
        repeat 3 {
          # one round
          stage "Move" 1m
          repeat 2 {
            wait 5s
          }
        }
        notify "Done"
    """.trimIndent()

    @Test fun parsingKeepsRepeatBlocksAndComments() {
        val o = Outline.parse(sample)
        assertEquals("Workout", o.name)
        assertEquals(AlertMode.SOUND, o.alerts)
        assertEquals(4, o.items.size)                      // comment, stage, repeat, notify
        assertIs<OutlineItem.Comment>(o.items[0])
        val rep = assertIs<OutlineItem.Repeat>(o.items[2])
        assertEquals(3, rep.count)
        assertEquals(3, rep.items.size)                    // comment, stage, inner repeat
        assertEquals(2, assertIs<OutlineItem.Repeat>(rep.items[2]).count)
    }

    @Test fun printThenParseGivesTheSameRunnableScript() {
        val o = Outline.parse(sample)
        val reprinted = o.print()
        assertEquals(o, Outline.parse(reprinted))
        assertEquals(ScriptParser.parse(sample), ScriptParser.parse(reprinted))
    }

    @Test fun printedTextUsesNaturalDurations() {
        assertEquals("90s", ScriptPrinter.duration(90_000))
        assertEquals("2m", ScriptPrinter.duration(120_000))
        assertEquals("1h", ScriptPrinter.duration(3_600_000))
        assertEquals("250ms", ScriptPrinter.duration(250))
    }

    @Test fun everyActionRoundTripsThroughItsPrintedLine() {
        val actions = listOf(
            Action.Stage("Bloom", 30_000), Action.Wait(2_000), Action.Chime, Action.Vibrate(200),
            Action.Notify("Hi there"), Action.Volume(35), Action.Log("note"),
        )
        for (a in actions) assertEquals(a, ScriptParser.parseAction(ScriptPrinter.line(a)))
    }

    @Test fun insertRemoveReplaceMoveWorkAtAnyDepth() {
        var o = Outline.parse(sample)
        o = o.insert(listOf(2), 0, OutlineItem.Step(Action.Chime))          // inside the 3x repeat, first
        assertEquals(OutlineItem.Step(Action.Chime), o.itemAt(listOf(2, 0)))
        o = o.insert(emptyList(), 99, OutlineItem.Step(Action.Log("end")))  // index is clamped
        assertEquals(OutlineItem.Step(Action.Log("end")), o.items.last())
        o = o.replace(listOf(2, 0), OutlineItem.Step(Action.Vibrate(100)))
        assertEquals(OutlineItem.Step(Action.Vibrate(100)), o.itemAt(listOf(2, 0)))
        o = o.move(listOf(2, 0), +1)                                        // swap with the comment
        assertIs<OutlineItem.Comment>(o.itemAt(listOf(2, 0)))
        assertEquals(OutlineItem.Step(Action.Vibrate(100)), o.itemAt(listOf(2, 1)))
        assertEquals(o, o.move(listOf(0), -1))                              // no-op at the top
        o = o.remove(listOf(2, 1))
        assertNull(o.itemAt(listOf(9)))
        ScriptParser.parse(o.print())                                       // still a valid script
    }

    @Test fun totalsCountRepeatsWithoutExpandingThem() {
        val o = Outline.parse("repeat 100 {\nrepeat 100 {\nwait 1s\n}\n}")
        assertEquals(10_000, o.actionCount)
        assertEquals(10_000_000, o.totalMs)
        // Over the limits, but still editable: only ScriptParser.parse enforces them.
        assertFailsWith<ScriptParseException> { ScriptParser.parse("repeat 100 {\nrepeat 100 {\nwait 1s\n}\n}") }
    }

    @Test fun headerOnlyScriptsAreEditableButNotRunnable() {
        val o = Outline.parse("routine \"Empty\"\nalerts none\n")
        assertEquals(emptyList(), o.items)
        assertFailsWith<ScriptParseException> { ScriptParser.parse(o.print()) }
    }

    @Test fun invalidScriptsAreRejectedWithLineNumbers() {
        val e = assertFailsWith<ScriptParseException> { Outline.parse("chime\nbogus") }
        assertEquals("Line 2: Unknown command: bogus", e.message)
    }

    @Test fun runnerPublishesProgressForTheRunScreen() = kotlinx.coroutines.test.runTest {
        val registry = ActionRegistry.default()
        val platform = object : Platform {
            override fun chime() {}
            override fun vibrate(durationMs: Long) {}
            override fun notify(title: String, message: String) {}
            override fun setMediaVolume(percent: Int) {}
            override fun log(message: String) {}
        }
        val runner = AutomationRunner(registry, platform, Clock { testScheduler.currentTime }, backgroundScope)
        runner.start(ScriptParser.parse("alerts none\nstage \"A\" 10s\nstage \"B\" 20s\nwait 5s"))
        testScheduler.runCurrent()
        var s = runner.state.value
        assertEquals(35_000, s.totalMs); assertEquals(10_000, s.stepTotalMs); assertEquals(0, s.elapsedBeforeStepMs)
        assertEquals("B", s.nextLabel)
        testScheduler.advanceTimeBy(10_001); testScheduler.runCurrent()
        s = runner.state.value
        assertEquals(20_000, s.stepTotalMs); assertEquals(10_000, s.elapsedBeforeStepMs); assertNull(s.nextLabel)
    }
}
