package dev.runlet.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineTemplatesTest {
    @Test fun everyTemplateIsARunnableScriptNamedAfterItself() {
        for (t in RoutineTemplates.all) {
            val a = ScriptParser.parse(t.source)
            assertEquals(t.title, a.name, t.id)
            assertTrue(a.totalMs > 0, t.id)
        }
    }

    @Test fun templateIdsAreUniqueAndBlankIsOnlyEditable() {
        assertEquals(RoutineTemplates.all.size, RoutineTemplates.all.map { it.id }.toSet().size)
        assertNotNull(RoutineTemplates.byId("blank"))
        Outline.parse(RoutineTemplates.blank.source)        // parses, so it opens in the editor...
        assertTrue(runCatching { ScriptParser.parse(RoutineTemplates.blank.source) }.isFailure)  // ...but is not runnable yet
        assertNull(RoutineTemplates.byId("nope"))
    }

    @Test fun routineNameHelpers() {
        val src = "# hi\nroutine \"Old name\"\nchime\n"
        assertEquals("Old name", ScriptText.routineName(src))
        assertEquals("# hi\nroutine \"New\"\nchime\n", ScriptText.renameRoutine(src, "New"))
        assertEquals("routine \"X\"\nchime", ScriptText.renameRoutine("chime", "X"))
        assertNull(ScriptText.routineName("chime"))
        assertTrue(ScriptText.isValidName("Morning run"))
        assertFalse(ScriptText.isValidName("  "))
        assertFalse(ScriptText.isValidName("say \"hi\""))
        assertFalse(ScriptText.isValidName("x".repeat(81)))
        // a name with regex-special characters must be written literally
        assertEquals("routine \"A\$1 \\\\ b\"\nchime", ScriptText.renameRoutine("routine \"z\"\nchime", "A\$1 \\\\ b"))
    }
}
