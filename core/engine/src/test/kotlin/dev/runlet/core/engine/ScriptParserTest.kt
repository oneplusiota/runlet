package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Ports the 21 assertions from the Java prototype's tests/ScriptTest.java. */
class ScriptParserTest {
    private fun bad(source: String) {
        assertFailsWith<ScriptParseException>(source) { ScriptParser.parse(source) }
    }

    @Test fun coffeePreset() {
        val p = ScriptParser.parse(ScriptParser.COFFEE)
        assertEquals(290_000, p.totalMs)
        assertEquals(4, p.actions.size)
        assertEquals("French press", p.name)
        assertEquals(AlertMode.BOTH, p.alerts)
    }

    @Test fun nestedRepeatsExpand() {
        val p = ScriptParser.parse("repeat 3 {\nrepeat 2 {\nwait 1s\n}\n}")
        assertEquals(6_000, p.totalMs)
        assertEquals(6, p.actions.size)
    }

    @Test fun demoPreset() {
        val p = ScriptParser.parse(ScriptParser.DEMO)
        assertEquals(7_000, p.totalMs)
        assertEquals(5, p.actions.size)
    }

    @Test fun rejectsInvalidScripts() {
        bad(ScriptParser.YOGA); bad("stage \"Bad\" 0s"); bad("wait -1s"); bad("wait 99999999999999999999999999h")
        bad("repeat 2 {\nwait 1s"); bad("}"); bad("repeat 100 {\nrepeat 100 {\nchime\n}\n}")
        bad("wait 4h\nwait 1s"); bad("vibrate 6s"); bad("volume 101"); bad("notify \"Hello\" trailing")
        bad("repeat 2 {\nalerts both\n}"); bad("shell rm -rf /")
    }

    @Test fun allSimpleActionsAreTyped() {
        val a = ScriptParser.parse("volume 0\nchime\nvibrate 200ms\nnotify \"Hi\"\nlog \"Ready\"").actions
        assertEquals(5, a.size)
        assertEquals(Action.Volume(0), a[0])
        assertIs<Action.Chime>(a[1])
        assertEquals(Action.Vibrate(200), a[2])
        assertEquals(Action.Notify("Hi"), a[3])
        assertEquals(Action.Log("Ready"), a[4])
    }

    @Test fun errorsCarryLineNumbers() {
        val e = assertFailsWith<ScriptParseException> { ScriptParser.parse("chime\nbogus") }
        assertEquals("Line 2: Unknown command: bogus", e.message)
    }
}
