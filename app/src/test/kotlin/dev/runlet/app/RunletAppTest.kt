package dev.runlet.app

import android.content.Context
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.runlet.app.ui.HostActions
import dev.runlet.app.ui.RunletApp
import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.data.RunHistoryRepository
import dev.runlet.core.data.RunletDatabase
import dev.runlet.core.engine.ActionRegistry
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.Platform
import dev.runlet.core.platform.RunCoordinator
import dev.runlet.core.platform.RunletServices
import dev.runlet.core.platform.TriggerScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeHost : HostActions {
    val started = mutableListOf<Long>()
    var importRequests = 0
    val exportNames = mutableListOf<String>()
    val backupNames = mutableListOf<String>()
    val shared = mutableListOf<Pair<String, String>>()
    val copied = mutableListOf<Pair<String, String>>()
    var clipboard: String? = null
    var blockRun = false
    override fun ensureCanRun(onReady: () -> Unit) { if (!blockRun) onReady() }
    override fun startRun(automationId: Long) { started += automationId }
    override fun pickImport() { importRequests++ }
    override fun pickExport(suggestedName: String) { exportNames += suggestedName }
    override fun pickBackup(suggestedFileName: String) { backupNames += suggestedFileName }
    override fun share(name: String, source: String) { shared += name to source }
    override fun copyText(label: String, text: String) { copied += label to text }
    override fun clipboardText(): String? = clipboard
    var exactAllowed = true
    var exactSettingsOpened = 0
    override fun openAppSettings() {}
    override fun canScheduleExactAlarms() = exactAllowed
    override fun openExactAlarmSettings() { exactSettingsOpened++ }
}

private object SilentPlatform : Platform {
    override fun chime() {}
    override fun vibrate(durationMs: Long) {}
    override fun notify(title: String, message: String) {}
    override fun setMediaVolume(percent: Int) {}
    override fun log(message: String) {}
}

// A phone-sized, low-density window: lazy lists only compose what fits, and the default window is tiny.
@Config(qualifiers = "w411dp-h1400dp-mdpi")
@RunWith(RobolectricTestRunner::class)
class RunletAppTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: RunletDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var graph: RunletServices.Graph
    private lateinit var vm: MainViewModel
    private val host = FakeHost()

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RunletDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val clock = Clock { System.currentTimeMillis() }
        val automations = AutomationRepository(db.automations(), clock)
        val history = RunHistoryRepository(db.runs(), clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val coordinator = RunCoordinator(scope, ActionRegistry.default(), SilentPlatform, history, automations, clock)
        graph = RunletServices.Graph(db, automations, history, coordinator, TriggerScheduler(context, automations), 0)
        vm = MainViewModel(graph, io = Dispatchers.Main.immediate, autosaveDelayMs = 0)
        compose.setContent { RunletApp(vm, host) }
        compose.waitForIdle()
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun rows() = runBlocking { graph.automations.observeAll().first() }
    private fun names() = rows().map { it.name }
    private fun source(name: String) = rows().first { it.name == name }.source

    /**
     * Activates a control through its accessibility click action. Robolectric's simulated touches do not reach
     * bottom sheets or segmented buttons (they are verified with real touch on the emulator), but the click
     * action runs the same handler a tap does.
     */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    /** Taps the routine card with this name on the home list. */
    private fun openRoutine(name: String) {
        // Cards merge their children, so look in the unmerged tree for the name text.
        val nodes = compose.onAllNodesWithTag("routine-name", useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        val i = (0 until count).first { idx -> runCatching { nodes[idx].assertTextEquals(name) }.isSuccess }
        nodes[i].performScrollTo().performClick()
        compose.waitForIdle()
    }

    // --- Home ----------------------------------------------------------------------------------

    @Test fun firstLaunchShowsThePresetRoutines() {
        assertEquals(listOf("French press", "Morning yoga", "Quick test"), names())
        compose.onAllNodesWithTag("routine-card").assertCountEquals(3)
        compose.onAllNodesWithTag("routine-name", useUnmergedTree = true).onFirst().assertTextEquals("French press")
        compose.onNodeWithText("4 steps · 4 min 50 s").assertExists()
        compose.onNodeWithTag("new-routine").assertIsDisplayed()
    }

    @Test fun anUnrunnableRoutineSaysSoAndCannotBeRun() {
        compose.onNodeWithText("Needs attention").assertExists()
        // Morning yoga is the empty draft, so its play button is the only disabled one.
        val runButtons = compose.onAllNodesWithTag("routine-run")
        runButtons.assertCountEquals(3)
        runButtons[0].assertIsEnabled()
        runButtons[1].assertIsNotEnabled()
        runButtons[2].assertIsEnabled()
    }

    @Test fun playOnACardStartsThatRoutine() {
        compose.onAllNodesWithTag("routine-run")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(rows().first { it.name == "French press" }.id), host.started)
    }

    @Test fun permissionIsRequestedBeforeAnythingStarts() {
        host.blockRun = true
        compose.onAllNodesWithTag("routine-run")[0].performClick()
        compose.waitForIdle()
        assertTrue(host.started.isEmpty())
    }

    @Test fun newRoutineOffersTemplatesAndOpensTheEditor() {
        compose.onNodeWithTag("new-routine").performClick()
        compose.onNodeWithTag("template-sheet").assertIsDisplayed()
        compose.onNodeWithTag("template-pomodoro").tap()
        compose.waitForIdle()
        assertTrue("Pomodoro" in names())
        compose.onNodeWithTag("basic-editor").assertIsDisplayed()
        compose.onNodeWithTag("editor-title").assertTextContains("Pomodoro", substring = true)
        compose.onNodeWithTag("total-time").assertTextEquals("2 h 15 min")
    }

    @Test fun deleteAsksForConfirmationAndKeepsTheRest() {
        compose.onAllNodesWithTag("routine-menu")[0].performClick()
        compose.onNodeWithTag("menu-delete").performClick()
        compose.onNodeWithText("Delete “French press”?").assertExists()
        compose.onNodeWithTag("confirm-delete").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Morning yoga", "Quick test"), names())
    }

    @Test fun onlyArmedClockTriggersNeedExactAlarms() {
        val src = "routine \"T\"\nwhen time 07:30\nchime\n"
        fun row(enabled: Boolean, source: String = src) = dev.runlet.core.data.AutomationEntity(1, "T", source, 0, enabled)
        assertTrue(MainViewModel.summarize(row(true)).needsExactAlarm)
        assertEquals(false, MainViewModel.summarize(row(false)).needsExactAlarm)
        assertEquals(false, MainViewModel.summarize(row(true, "routine \"T\"\nwhen boot\nchime\n")).needsExactAlarm)
        assertEquals(listOf("Every day at 07:30"), MainViewModel.summarize(row(true)).triggers)
    }

    @Test fun duplicateUsesTheRightRoutine() {
        compose.onAllNodesWithTag("routine-menu")[0].performClick()
        compose.onNodeWithText("Duplicate").performClick()
        compose.waitForIdle()
        assertTrue("French press 2" in names())
    }

    @Test fun eachRoutineCanBeSavedSharedAndCopied() {
        compose.onAllNodesWithTag("routine-menu")[0].performClick()
        compose.onNodeWithTag("menu-export").performClick()
        assertEquals(listOf("French press"), host.exportNames)

        compose.onAllNodesWithTag("routine-menu")[0].performClick()
        compose.onNodeWithTag("menu-share").performClick()
        compose.waitForIdle()
        assertEquals(listOf("French press" to source("French press")), host.shared)

        compose.onAllNodesWithTag("routine-menu")[0].performClick()
        compose.onNodeWithTag("menu-copy").performClick()
        compose.waitForIdle()
        assertEquals(listOf("French press" to source("French press")), host.copied)
    }

    @Test fun theHomeMenuOffersImportPasteAndBackup() {
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-import").performClick()
        assertEquals(1, host.importRequests)

        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-backup").performClick()
        assertEquals(1, host.backupNames.size)
        assertTrue(host.backupNames.single().startsWith("Runlet backup ") && host.backupNames.single().endsWith(".zip"))
    }

    @Test fun pasteImportsARoutineFromTheClipboardAndOpensIt() {
        host.clipboard = "routine \"Stretch\"\nalerts none\nstage \"Reach\" 20s"
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-paste").performClick()
        compose.waitForIdle()
        assertTrue("Stretch" in names())
        compose.onNodeWithTag("editor-title").assertTextEquals("Stretch")      // opened for review
        assertTrue(host.started.isEmpty())                                      // never run
    }

    @Test fun pastingSomethingThatIsNotARoutineChangesNothing() {
        host.clipboard = "Are we still meeting at 5?"
        val before = names()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-paste").performClick()
        compose.waitForIdle()
        assertEquals(before, names())
        compose.onAllNodesWithTag("basic-editor").assertCountEquals(0)

        host.clipboard = null
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-paste").performClick()
        compose.waitForIdle()
        assertEquals(before, names())
    }

    // --- Editor: steps -------------------------------------------------------------------------

    @Test fun stepsShowAsCardsWithTotals() {
        openRoutine("French press")
        compose.onAllNodesWithTag("step-card").assertCountEquals(4)
        compose.onNodeWithTag("total-time").assertTextEquals("4 min 50 s")
        compose.onNodeWithText("Bloom").assertExists()
        compose.onNodeWithText("Ready to run").assertExists()
    }

    @Test fun addingAStepAppendsItAndSavesTheScript() {
        openRoutine("French press")
        compose.onNodeWithTag("add-step").performScrollTo().performClick()
        compose.onNodeWithTag("add-sheet").assertIsDisplayed()
        compose.onNodeWithTag("option-Chime").tap()
        compose.waitForIdle()
        compose.onAllNodesWithTag("step-card").assertCountEquals(5)
        assertTrue(source("French press").trimEnd().endsWith("chime"))
    }

    @Test fun editingAStageChangesItsNameAndTime() {
        openRoutine("French press")
        compose.onAllNodesWithTag("step-card")[0].performClick()
        compose.onNodeWithTag("step-sheet").assertIsDisplayed()
        compose.onNodeWithTag("field-label").performTextReplacement("Pre-infuse")
        compose.onNodeWithTag("up-min").tap()               // 30 s -> 1 min 30 s
        compose.onNodeWithTag("value-min").assertTextEquals("01")
        compose.onNodeWithTag("step-save").tap()
        compose.waitForIdle()
        assertTrue(source("French press").contains("stage \"Pre-infuse\" 90s"))
        compose.onNodeWithTag("total-time").assertTextEquals("5 min 50 s")
    }

    @Test fun stepsCanBeMovedAndRemoved() {
        openRoutine("French press")
        compose.onAllNodesWithTag("step-down")[0].performClick()
        compose.waitForIdle()
        val lines = source("French press").lines().filter { it.startsWith("stage") }
        assertEquals("stage \"Steep\" 210s", lines[0])
        assertEquals("stage \"Bloom\" 30s", lines[1])

        compose.onAllNodesWithTag("step-delete")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("step-card").assertCountEquals(3)
    }

    @Test fun repeatBlocksAreShownAndSurviveEdits() {
        openRoutine("Quick test")
        compose.onNodeWithText("Repeat 2 times").assertExists()
        compose.onNodeWithTag("repeat-header").performClick()
        compose.onNodeWithTag("repeat-plus").tap()
        compose.onNodeWithTag("repeat-count").assertTextEquals("3 times")
        compose.onNodeWithTag("repeat-save").tap()
        compose.waitForIdle()
        assertTrue(source("Quick test").contains("repeat 3 {"))
        assertTrue(source("Quick test").contains("log \"Testing your phone alerts\""))   // untouched steps are kept
    }

    @Test fun alertModeIsChangedWithAChip() {
        openRoutine("French press")
        compose.onNodeWithTag("alerts-none").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(source("French press").contains("alerts none"))
    }

    @Test fun triggersAreAddedAndRemovedFromTheWhenSection() {
        openRoutine("French press")
        compose.onNodeWithTag("add-trigger").performScrollTo().performClick()
        compose.onNodeWithTag("kind-boot").tap()
        compose.onNodeWithTag("trigger-save").tap()
        compose.waitForIdle()
        assertTrue(source("French press").contains("when boot"))
        compose.onNodeWithTag("trigger-card").performScrollTo().assertExists()
        compose.onNodeWithTag("trigger-delete", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(!source("French press").contains("when boot"))
    }

    @Test fun anEmptyRoutineInvitesYouToAddAStep() {
        openRoutine("Morning yoga")
        compose.onNodeWithTag("empty-steps").assertExists()
    }

    /**
     * The rename dialog itself is checked on the emulator: under Robolectric a dialog's blinking cursor keeps
     * the test clock from ever going idle. The rules behind it are checked here.
     */
    @Test fun renamingRewritesTheRoutineLineAndRejectsBadOrTakenNames() {
        openRoutine("French press")
        assertTrue(!vm.rename("Quick test"))                      // taken
        assertTrue(!vm.rename("say \"hi\""))                      // quotes can't be written into the script
        assertTrue(!vm.rename("   "))
        assertTrue(vm.rename("Aeropress"))
        compose.waitForIdle()
        compose.onNodeWithTag("editor-title").assertTextEquals("Aeropress")
        assertTrue("Aeropress" in names())
        assertTrue(source("Aeropress").contains("routine \"Aeropress\""))
        assertTrue(source("Aeropress").contains("stage \"Bloom\" 30s"))   // the rest of the script is untouched
    }

    // --- Editor: script view -------------------------------------------------------------------

    @Test fun scriptViewEditsTheSameTextAndValidatesLive() {
        openRoutine("French press")
        compose.onNodeWithTag("mode-script").tap()
        compose.waitForIdle()
        compose.onNodeWithTag("editor").performTextReplacement("chime\nbogus")
        compose.waitForIdle()
        compose.onNodeWithTag("validation").assertTextContains("Line 2: Unknown command: bogus")
        compose.onAllNodesWithTag("fallback-notice").assertCountEquals(0)         // deliberately in Script view
        compose.onNodeWithTag("editor").performTextReplacement("routine \"French press\"\nchime\nwait 3s")
        compose.waitForIdle()
        compose.onNodeWithTag("validation").assertTextContains("2 steps", substring = true)
        assertEquals("routine \"French press\"\nchime\nwait 3s", source("French press"))
        compose.onNodeWithTag("mode-steps").tap()
        compose.waitForIdle()
        compose.onAllNodesWithTag("step-card").assertCountEquals(2)
    }

    @Test fun aBrokenScriptFallsBackToTheScriptEditorWithAnExplanation() {
        openRoutine("French press")
        compose.onNodeWithTag("mode-script").tap()
        compose.onNodeWithTag("editor").performTextReplacement("routine \"French press\"\nstage \"x\"")
        compose.onNodeWithTag("mode-steps").tap()
        compose.waitForIdle()
        compose.onNodeWithTag("fallback-notice").assertIsDisplayed()
        assertEquals("routine \"French press\"\nstage \"x\"", source("French press"))   // saved even though invalid
    }

    @Test fun runIsRefusedWhileTheScriptIsInvalid() {
        openRoutine("French press")
        compose.onNodeWithTag("mode-script").tap()
        compose.onNodeWithTag("editor").performTextReplacement("bogus")
        compose.waitForIdle()
        compose.onNodeWithTag("editor-run").performClick()
        compose.waitForIdle()
        assertTrue(host.started.isEmpty())
    }

    @Test fun backReturnsToTheListAfterSaving() {
        openRoutine("French press")
        compose.onNodeWithTag("mode-script").tap()
        compose.onNodeWithTag("editor").performTextReplacement("routine \"French press\"\nchime")
        compose.waitForIdle()
        compose.onNodeWithTag("editor-back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("home-list").assertIsDisplayed()
        compose.onNodeWithText("1 step · 0 s").assertExists()
    }

    // --- Running -------------------------------------------------------------------------------

    @Test fun runningShowsTheMiniBarThenTheRunScreenAndStopReturnsToTheList() {
        // The fake host never starts the service, so start the run through the coordinator as the service would.
        runBlocking { graph.coordinator.startSaved(rows().first { it.name == "Quick test" }.id) }
        compose.waitForIdle()
        compose.onNodeWithTag("mini-run").assertIsDisplayed()
        compose.onNodeWithTag("mini-run").performClick()
        compose.onNodeWithTag("run-screen").assertIsDisplayed()
        compose.onNodeWithTag("run-pill").assertTextEquals("Running")
        compose.onNodeWithTag("run-label").assertTextEquals("First stage")
        compose.onNodeWithTag("run-next").assertTextEquals("Up next: Repeat stage")
        compose.onNodeWithTag("run-pause").performClick()
        compose.onNodeWithTag("run-pill").assertTextEquals("Paused")
        compose.onNodeWithTag("run-pause").performClick()
        compose.onNodeWithTag("run-pill").assertTextEquals("Running")
        compose.onNodeWithTag("run-stop").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("run-screen").assertCountEquals(0)
        compose.onAllNodesWithTag("mini-run").assertCountEquals(0)
    }

    // --- Activity and guide --------------------------------------------------------------------

    @Test fun activityListsRunsAndShowsLogsWhenOpened() {
        compose.onNodeWithTag("tab-Activity").performClick()
        compose.onNodeWithTag("no-runs").assertExists()

        runBlocking {
            val id = graph.history.begin(null, "Ghost")
            graph.history.log(id, "hello log")
            graph.history.finish(id, "COMPLETED")
        }
        compose.waitForIdle()
        compose.onAllNodesWithTag("run-row").assertCountEquals(1)
        compose.onNodeWithText("Ghost").assertExists()
        compose.onNodeWithTag("run-row").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("hello log").assertExists()
        compose.onNodeWithTag("clear-history").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("no-runs").assertExists()
    }

    @Test fun guideExplainsTheCommands() {
        compose.onNodeWithTag("tab-Guide").performClick()
        compose.onNodeWithTag("guide").assertIsDisplayed()
        compose.onNodeWithText("stage \"Name\" 30s").assertExists()
    }
}
