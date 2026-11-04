package dev.runlet.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.data.RunHistoryRepository
import dev.runlet.core.data.RunletDatabase
import dev.runlet.core.engine.ActionRegistry
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.Platform
import dev.runlet.core.engine.RoutineBundle
import dev.runlet.core.engine.RoutineFile
import dev.runlet.core.engine.ScriptParser
import dev.runlet.core.platform.RunCoordinator
import dev.runlet.core.platform.RunletServices
import dev.runlet.core.platform.TriggerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real import/export code against real files. The system file pickers cannot be used from a unit test,
 * but everything behind them — reading, validating, saving, writing — can, and that is where the bugs would be.
 */
@RunWith(RobolectricTestRunner::class)
class ImportExportTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: RunletDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var graph: RunletServices.Graph
    private lateinit var vm: MainViewModel

    private object Silent : Platform {
        override fun chime() {}
        override fun vibrate(durationMs: Long) {}
        override fun notify(title: String, message: String) {}
        override fun setMediaVolume(percent: Int) {}
        override fun log(message: String) {}
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RunletDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val clock = Clock { System.currentTimeMillis() }
        val automations = AutomationRepository(db.automations(), clock)
        val history = RunHistoryRepository(db.runs(), clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        graph = RunletServices.Graph(db, automations, history, RunCoordinator(scope, ActionRegistry.default(), Silent, history, automations, clock), TriggerScheduler(context, automations), 0)
        // Start from an empty library so counts are exact; marking it seeded keeps the presets away.
        vm = MainViewModel(graph, io = Dispatchers.Main.immediate, autosaveDelayMs = 0, prefs = UiPrefs.InMemory(seeded = true))
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun rows() = runBlocking { graph.automations.observeAll().first() }
    private fun file(name: String, bytes: ByteArray): Uri = Uri.fromFile(tmp.newFile(name).also { it.writeBytes(bytes) })
    private fun file(name: String, text: String) = file(name, text.toByteArray())
    private fun target(name: String): Pair<Uri, File> = tmp.newFile(name).let { Uri.fromFile(it) to it }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in entries) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    /** Runs [block] and returns every user message it produced. Collects eagerly so none are missed. */
    private fun messages(block: () -> Unit): List<String> {
        val seen = mutableListOf<String>()
        val collector = CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
            vm.messages.collect { seen += it }
        }
        try { block() } finally { collector.cancel() }
        return seen
    }

    // --- import ------------------------------------------------------------------------------------

    @Test fun importingAFileCreatesADraftAndOpensItWithoutRunningIt() {
        val said = messages { vm.importFrom(context.contentResolver, file("french.routine", ScriptParser.COFFEE)) }
        val row = rows().single()
        assertEquals("French press", row.name)
        assertEquals(ScriptParser.COFFEE, row.source)
        assertEquals(row.id, vm.selectedId.value)                       // opened for review
        assertEquals("Imported “French press”; review before running", said.single())
        assertEquals(dev.runlet.core.engine.RunStatus.IDLE, vm.runState.value.status)
    }

    @Test fun anUnfinishedRoutineIsStillImportedSoItCanBeFixed() {
        vm.importFrom(context.contentResolver, file("yoga.routine", ScriptParser.YOGA))
        assertEquals(ScriptParser.YOGA, rows().single().source)
    }

    @Test fun importingTheSameFileTwiceKeepsBothUnderDifferentNames() {
        val uri = file("a.routine", ScriptParser.COFFEE)
        vm.importFrom(context.contentResolver, uri)
        vm.importFrom(context.contentResolver, uri)
        assertEquals(listOf("French press", "French press 2"), rows().map { it.name })
    }

    @Test fun aBackupArchiveImportsEveryRoutineInIt() {
        val zip = zipOf("one.routine" to ScriptParser.COFFEE, "two.routine" to ScriptParser.DEMO, "notes.txt" to "wait 5s")
        val said = messages { vm.importFrom(context.contentResolver, file("backup.zip", zip)) }
        // notes.txt held only "wait 5s": it parses, and an unnamed routine is called "Untitled routine".
        assertEquals(listOf("French press", "Quick test", "Untitled routine"), rows().map { it.name }.sorted())
        assertEquals("Imported 3 routines; review before running", said.single())
        assertEquals(null, vm.selectedId.value)                         // several routines: stay on the list
    }

    @Test fun anArchiveWithNothingUsableSaysSoAndChangesNothing() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1)
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("pic.png")); z.write(png); z.closeEntry() }
        val said = messages { vm.importFrom(context.contentResolver, file("pics.zip", out.toByteArray())) }
        assertTrue(rows().isEmpty())
        assertEquals("No routines were found in that file", said.single())
    }

    @Test fun binaryFilesAreRejectedWithAClearMessage() {
        val said = messages { vm.importFrom(context.contentResolver, file("photo.routine", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1))) }
        assertTrue(rows().isEmpty())
        assertEquals("Import failed: That is not a text file", said.single())
    }

    @Test fun oversizedFilesAreRejectedWithoutBeingFullyLoaded() {
        val said = messages { vm.importFrom(context.contentResolver, file("huge.routine", ByteArray(RoutineBundle.MAX_FILE_BYTES + 1) { 'a'.code.toByte() })) }
        assertTrue(rows().isEmpty())
        assertTrue(said.single().startsWith("Import failed:"))
    }

    @Test fun aMissingFileFailsWithAMessageInsteadOfCrashing() {
        val said = messages { vm.importFrom(context.contentResolver, Uri.fromFile(File(tmp.root, "gone.routine"))) }
        assertTrue(rows().isEmpty())
        assertTrue(said.single().startsWith("Import failed:"), said.toString())
    }

    // --- pasted and shared text --------------------------------------------------------------------

    @Test fun pastedRoutineTextIsImported() {
        vm.importText("routine \"Pasted\"\nchime")
        assertEquals("Pasted", rows().single().name)
    }

    @Test fun pastedProseIsRefusedAndNothingIsCreated() {
        val said = messages { vm.importText("see you at the standup tomorrow") }
        assertTrue(rows().isEmpty())
        assertEquals("That text doesn't look like a routine", said.single())
        assertEquals("There is nothing to paste", messages { vm.importText("  ") }.single())
    }

    // --- export ------------------------------------------------------------------------------------

    @Test fun exportingWritesTheRoutinesExactTextToTheChosenFile() {
        vm.importFrom(context.contentResolver, file("a.routine", ScriptParser.DEMO))
        val id = rows().single().id
        val (uri, out) = target("Quick test.routine")
        assertEquals("Quick test", vm.beginExport(id))
        val said = messages { vm.exportTo(context.contentResolver, uri) }
        assertEquals(ScriptParser.DEMO, out.readText())
        assertEquals("Exported “Quick test”", said.single())
    }

    @Test fun exportingOverAnExistingLongerFileDoesNotLeaveOldTextBehind() {
        vm.importFrom(context.contentResolver, file("a.routine", "routine \"S\"\nchime"))
        val (uri, out) = target("S.routine")
        out.writeText("x".repeat(5_000))                                 // an older, longer file being replaced
        vm.beginExport(rows().single().id)
        vm.exportTo(context.contentResolver, uri)
        assertEquals("routine \"S\"\nchime", out.readText())
    }

    @Test fun exportsIncludeEditsThatHaveNotBeenAutosavedYet() {
        vm.importFrom(context.contentResolver, file("a.routine", ScriptParser.COFFEE))   // opens the editor
        vm.onEdit("routine \"French press\"\nchime")
        val (uri, out) = target("x.routine")
        vm.beginExport(rows().single().id)
        vm.exportTo(context.contentResolver, uri)
        assertEquals("routine \"French press\"\nchime", out.readText())
    }

    @Test fun exportWithoutAChosenRoutineIsRefusedPolitely() {
        val (uri, out) = target("x.routine")
        val said = messages { vm.exportTo(context.contentResolver, uri) }
        assertEquals("", out.readText())
        assertEquals("The export was interrupted. Please try again.", said.single())
    }

    // --- backup round trip -------------------------------------------------------------------------

    @Test fun aBackupOfEverythingRestoresIntoAFreshInstall() {
        listOf(ScriptParser.COFFEE, ScriptParser.DEMO, ScriptParser.YOGA).forEachIndexed { i, src -> vm.importFrom(context.contentResolver, file("r$i.routine", src)) }
        val originals = rows().associate { it.name to it.source }
        assertEquals(3, originals.size)

        val name = vm.beginBackup()
        assertTrue(name.startsWith("Runlet backup ") && name.endsWith(".zip"))
        val (uri, out) = target("backup.zip")
        val said = messages { vm.exportTo(context.contentResolver, uri) }
        assertEquals("Backed up 3 routines", said.single())

        // Wipe the library, then restore from the file we just wrote.
        for (r in rows()) runBlocking { graph.automations.delete(r.id) }
        assertTrue(rows().isEmpty())
        vm.importFrom(context.contentResolver, Uri.fromFile(out))
        assertEquals(originals, rows().associate { it.name to it.source })
    }

    @Test fun backingUpAnEmptyLibraryWritesNothing() {
        vm.beginBackup()
        val (uri, out) = target("backup.zip")
        val said = messages { vm.exportTo(context.contentResolver, uri) }
        assertEquals(0L, out.length())
        assertEquals("There are no routines to back up", said.single())
    }

    @Test fun routinesWithUnusualNamesSurviveABackupRoundTrip() {
        val src = "routine \"Café ☕ / 朝\"\nnotify \"こんにちは\""
        vm.importText(src)
        vm.beginBackup()
        val (uri, out) = target("b.zip")
        vm.exportTo(context.contentResolver, uri)
        for (r in rows()) runBlocking { graph.automations.delete(r.id) }
        vm.importFrom(context.contentResolver, Uri.fromFile(out))
        assertEquals(src, rows().single().source)
    }
}
