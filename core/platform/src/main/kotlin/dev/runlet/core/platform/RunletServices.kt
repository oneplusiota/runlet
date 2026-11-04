package dev.runlet.core.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.data.RunHistoryRepository
import dev.runlet.core.data.RunletDatabase
import dev.runlet.core.engine.ActionRegistry
import dev.runlet.core.engine.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Launches the host app's main screen. Used by notifications so this module needs no app class. */
internal fun openAppIntent(context: Context): PendingIntent {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
    return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Process-wide wiring until a DI framework is added. Call [init] from `Application.onCreate`.
 * The scope runs on the main thread: the engine and service share one serial context, as the
 * prototype's single Handler did.
 */
@OptIn(FlowPreview::class)
object RunletServices {
    @Volatile private var instance: Graph? = null

    class Graph(
        val database: RunletDatabase,
        val automations: AutomationRepository,
        val history: RunHistoryRepository,
        val coordinator: RunCoordinator,
        val triggers: TriggerScheduler,
        val notificationIcon: Int,
    )

    fun init(context: Context, notificationIcon: Int): Graph = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext, notificationIcon).also { instance = it }
    }

    fun get(): Graph = checkNotNull(instance) { "RunletServices.init must be called from Application.onCreate" }

    private fun build(context: Context, notificationIcon: Int): Graph {
        val db = RunletDatabase.create(context)
        // Stored timestamps must be wall-clock so they can be shown as dates and survive reboots;
        // the runner needs the monotonic clock so waits ignore clock changes and count deep sleep.
        val wallClock = Clock { System.currentTimeMillis() }
        val clock = ElapsedRealtimeClock
        val automations = AutomationRepository(db.automations(), wallClock)
        val history = RunHistoryRepository(db.runs(), wallClock)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val platform = AndroidPlatform(context, notificationIcon) { Log.i("Runlet", it) }
        val coordinator = RunCoordinator(scope, ActionRegistry.default(), platform, history, automations, clock)
        val triggers = TriggerScheduler(context, automations, lastRunMs = { history.lastStartedAt(it) })
        // Any change to the saved automations (edit, enable, delete, import) re-arms the alarm.
        scope.launch {
            automations.observeAll().debounce(500).collect { runCatching { triggers.reschedule() } }
        }
        return Graph(db, automations, history, coordinator, triggers, notificationIcon)
    }
}
