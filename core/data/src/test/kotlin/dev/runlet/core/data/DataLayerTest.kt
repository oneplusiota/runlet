package dev.runlet.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.ScriptParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class DataLayerTest {
    private lateinit var db: RunletDatabase
    private var now = 1_000L
    private val clock = Clock { now }
    private lateinit var automations: AutomationRepository
    private lateinit var history: RunHistoryRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RunletDatabase::class.java).allowMainThreadQueries().build()
        automations = AutomationRepository(db.automations(), clock)
        history = RunHistoryRepository(db.runs(), clock)
    }

    @After fun tearDown() = db.close()

    @Test fun savesAndReadsBackAutomationNamedByScript() = runBlocking {
        val id = automations.save(null, ScriptParser.COFFEE)
        val row = assertNotNull(automations.get(id))
        assertEquals("French press", row.name)
        assertEquals(ScriptParser.COFFEE, row.source)
        assertEquals(listOf("French press"), automations.observeAll().first().map { it.name })
    }

    @Test fun skippedRunsAreRecordedAndSurviveOrphanCleanup() = runBlocking {
        val id = automations.save(null, ScriptParser.COFFEE)
        history.recordSkipped(id, "French press", "Tea was already running")
        history.closeOrphanedRuns()
        val run = history.observeRecent().first().single()
        assertEquals("SKIPPED", run.status)
        assertEquals("Tea was already running", run.error)
        assertEquals(run.startedAtMs, run.endedAtMs)
    }

    @Test fun updateKeepsIdAndFollowsRenamedRoutine() = runBlocking {
        val id = automations.save(null, ScriptParser.COFFEE)
        now = 2_000
        automations.save(id, ScriptParser.COFFEE.replace("French press", "Tea"))
        val row = assertNotNull(automations.get(id))
        assertEquals("Tea", row.name)
        assertEquals(2_000, row.updatedAtMs)
        assertEquals(1, automations.observeAll().first().size)
    }

    @Test fun rejectsInvalidScriptsAndDuplicateNames() = runBlocking {
        assertFailsWith<AutomationSaveException> { automations.save(null, "bogus") }
        automations.save(null, ScriptParser.COFFEE)
        val e = assertFailsWith<AutomationSaveException> { automations.save(null, ScriptParser.COFFEE) }
        assertEquals("An automation named “French press” already exists", e.message)
    }

    @Test fun draftsKeepInvalidTextAndFollowValidRenames() = runBlocking {
        val id = automations.save(null, ScriptParser.COFFEE)
        automations.saveDraft(id, "routine \"French press\"\nstage \"Bloom\"")      // mid-edit, invalid
        assertEquals("stage \"Bloom\"", automations.get(id)!!.source.lines().last())
        assertEquals("French press", automations.get(id)!!.name)
        automations.saveDraft(id, ScriptParser.COFFEE.replace("French press", "Tea"))
        assertEquals("Tea", automations.get(id)!!.name)
    }

    @Test fun draftKeepsOldNameWhenNewNameBelongsToAnother() = runBlocking {
        automations.save(null, ScriptParser.COFFEE)
        val other = automations.save(null, ScriptParser.DEMO)
        val clash = ScriptParser.DEMO.replace("Quick test", "French press")
        automations.saveDraft(other, clash)
        val row = automations.get(other)!!
        assertEquals("Quick test", row.name)
        assertEquals(clash, row.source)
    }

    @Test fun createDraftValidatesNameAndStartsEditable() = runBlocking {
        val id = automations.createDraft("  Stretch  ")
        assertEquals("Stretch", automations.get(id)!!.name)
        assertEquals("routine \"Stretch\"\nalerts both\n\n# Add your actions here\n", automations.get(id)!!.source)
        assertFailsWith<AutomationSaveException> { automations.createDraft("") }
        assertFailsWith<AutomationSaveException> { automations.createDraft("has \"quote\"") }
        assertFailsWith<AutomationSaveException> { automations.createDraft("x".repeat(81)) }
        assertFailsWith<AutomationSaveException> { automations.createDraft("Stretch") }
        Unit
    }

    @Test fun importKeepsInvalidTextAndSuffixesNameClashes() = runBlocking {
        automations.save(null, ScriptParser.COFFEE)
        val dup = automations.importSource(ScriptParser.COFFEE)
        assertEquals("French press 2", automations.get(dup)!!.name)
        val draft = automations.importSource(ScriptParser.YOGA)   // intentionally invalid
        assertEquals("Morning yoga", automations.get(draft)!!.name)          // named by its routine line
        assertEquals(ScriptParser.YOGA, automations.get(draft)!!.source)
        assertEquals("Imported draft", automations.get(automations.importSource("nonsense"))!!.name)
        assertEquals("Imported draft 2", automations.get(automations.importSource("more nonsense"))!!.name)
    }

    @Test fun aRenamedDuplicateAlsoRenamesItsRoutineLine() = runBlocking {
        automations.save(null, ScriptParser.COFFEE)
        val dup = automations.get(automations.importSource(ScriptParser.COFFEE))!!
        assertEquals("French press 2", dup.name)
        assertEquals("French press 2", dev.runlet.core.engine.ScriptText.routineName(dup.source))
        assertEquals(ScriptParser.COFFEE.replace("French press", "French press 2"), dup.source)
        // Unnamed text is stored untouched, even when its draft name had to be made unique.
        automations.importSource("nonsense")
        assertEquals("nonsense", automations.get(automations.importSource("nonsense"))!!.source)
        Unit
    }

    @Test fun runHistoryRecordsLogsAndOutcome() = runBlocking {
        val id = automations.save(null, ScriptParser.COFFEE)
        val run = history.begin(id, "French press")
        history.log(run, "Started")
        history.log(run, "Bloom · 00:30")
        now = 5_000
        history.finish(run, "COMPLETED")
        val row = history.observeRecent().first().single()
        assertEquals("COMPLETED", row.status)
        assertEquals(5_000, row.endedAtMs)
        assertEquals(listOf("Started", "Bloom · 00:30"), history.observeLogs(run).first().map { it.message })
    }

    @Test fun deletingAutomationKeepsItsHistory() = runBlocking {
        val id = automations.save(null, ScriptParser.COFFEE)
        val run = history.begin(id, "French press")
        automations.delete(id)
        val row = history.observeRecent().first().single { it.id == run }
        assertNull(row.automationId)
        assertEquals("French press", row.automationName)
    }

    @Test fun orphanedRunsAreClosedOnNextLaunch() = runBlocking {
        val active = history.begin(null, "A")
        val done = history.begin(null, "B").also { history.finish(it, "COMPLETED") }
        now = 9_000
        assertEquals(1, history.closeOrphanedRuns())
        val rows = history.observeRecent().first().associateBy { it.id }
        assertEquals("STOPPED", rows.getValue(active).status)
        assertEquals("Interrupted", rows.getValue(active).error)
        assertEquals("COMPLETED", rows.getValue(done).status)
    }

    @Test fun historyIsTrimmedToNewest200AndLogsCascade() = runBlocking {
        val first = history.begin(null, "old")
        history.log(first, "x")
        repeat(200) { now += 1; history.begin(null, "r$it") }
        val runs = history.observeRecent(500).first()
        assertEquals(200, runs.size)
        assertEquals(0, history.observeLogs(first).first().size)
    }
}
