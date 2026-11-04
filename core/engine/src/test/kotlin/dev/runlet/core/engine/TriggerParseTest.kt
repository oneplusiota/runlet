package dev.runlet.core.engine

import dev.runlet.core.model.Day
import dev.runlet.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TriggerParseTest {
    private fun triggers(vararg lines: String) =
        ScriptParser.parse("routine \"T\"\n${lines.joinToString("\n")}\nchime\n").triggers

    @Test fun parsesAllForms() {
        assertEquals(
            listOf(
                Trigger.Time(7, 30, Day.WEEKDAYS),
                Trigger.Time(22, 0, Day.ALL),
                Trigger.Time(9, 5, setOf(Day.MON, Day.WED)),
                Trigger.Boot,
            ),
            triggers(
                "when time 07:30 weekdays", "when time 22:00 daily", "when time 09:05 mon,wed",
                "when boot",
            ),
        )
    }

    @Test fun roundTripsThroughOutline() {
        val src = "routine \"T\"\nalerts both\nwhen time 07:30 weekdays\nwhen time 09:05 mon,wed\nwhen boot\n\nchime\n"
        assertEquals(src, ScriptParser.parseOutline(src).print())
    }

    @Test fun outlineEditsTriggers() {
        val base = ScriptParser.parseOutline("routine \"T\"\nchime\n")
        val a = Trigger.Time(7, 30)
        val edited = base.addTrigger(a).addTrigger(a).addTrigger(Trigger.Boot)
        assertEquals(listOf(a, Trigger.Boot), edited.triggers) // duplicate ignored
        assertEquals(listOf(Trigger.Time(8, 0), Trigger.Boot), edited.replaceTrigger(0, Trigger.Time(8, 0)).triggers)
        assertEquals(listOf(Trigger.Boot), edited.removeTrigger(0).triggers)
        assertEquals(edited, edited.removeTrigger(9))
        // survives the print/parse round trip the editor relies on
        assertEquals(edited.triggers, ScriptParser.parseOutline(edited.print()).triggers)
    }

    @Test fun noTriggersByDefault() = assertEquals(emptyList(), triggers())

    @Test fun rejectsBadTriggers() {
        for (bad in listOf("when", "when time", "when time 25:00", "when time 7:30", "when time 07:30 funday",
            "when time 07:30 mon extra", "when boot now", "when power connected", "when moon")) {
            assertFailsWith<ScriptParseException>(bad) { triggers(bad) }
        }
    }

    @Test fun rejectsWhenInsideRepeat() {
        assertFailsWith<ScriptParseException> { ScriptParser.parse("repeat 2 {\nwhen boot\nchime\n}") }
    }
}
