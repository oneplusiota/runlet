package dev.runlet.core.engine

import dev.runlet.core.model.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Guards the registry as the single place an action is defined: add a type and these cover it automatically. */
class ActionRegistryTest {
    private val registry = ActionRegistry.default()

    @Test fun everyTypeRoundTripsItsOwnNewAction() {
        for (type in registry.all()) {
            @Suppress("UNCHECKED_CAST")
            val t = type as ActionType<Action>
            val fresh = t.newAction()
            val text = t.print(fresh)
            assertEquals(t.id, text.substringBefore(' '), "${t.id} prints its own keyword")
            assertEquals(fresh, ScriptParser.parseAction(text), "${t.id} survives print then parse")
            assertTrue(t.title.isNotBlank() && t.hint.isNotBlank(), "${t.id} has menu text")
        }
    }

    @Test fun idsAreUniqueAndLookUpByKeyword() {
        val ids = registry.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertNotNull(registry.type(id), id)
    }

    @Test fun reservedHeaderWordsAreNotActions() {
        for (word in listOf("routine", "alerts", "when", "repeat")) assertEquals(null, registry.type(word), word)
    }

    @Test fun duplicateRegistrationIsRejected() {
        assertFailsWith<IllegalArgumentException> { ActionRegistry().register(BuiltInActions.LogType).register(BuiltInActions.LogType) }
    }
}
