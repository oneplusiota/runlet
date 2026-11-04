package dev.runlet.core.platform

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.runlet.core.engine.RunState
import dev.runlet.core.engine.RunStatus
import dev.runlet.core.engine.formatClock
import dev.runlet.core.model.Limits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground host for a run: shows the notification, holds the wake lock, and forwards
 * controls to [RunCoordinator]. The run itself lives in the coordinator, not here.
 */
class RunnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Runlet:Run")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val services = RunletServices.get()
        val coordinator = services.coordinator
        when (intent?.action) {
            ACTION_RUN -> {
                // A trigger never interrupts a run the user is already in; the service is foreground already.
                if (intent.getBooleanExtra(EXTRA_TRIGGERED, false) && coordinator.isActive && foreground) {
                    coordinator.recordSkipped(
                        intent.getLongExtra(EXTRA_AUTOMATION_ID, 0),
                        "${coordinator.state.value.automationName} was already running",
                    )
                    return START_NOT_STICKY
                }
                // startForegroundService() obliges us to call startForeground() promptly.
                enterForeground(placeholder(services.notificationIcon))
                val id = intent.getLongExtra(EXTRA_AUTOMATION_ID, 0)
                scope.launch {
                    try {
                        coordinator.startSaved(id)
                        observe()
                    } catch (e: Exception) {
                        services.coordinator.reportFailure(e.message ?: "Could not start the routine")
                        finish()
                    }
                }
            }
            ACTION_PAUSE -> coordinator.pause()
            ACTION_RESUME -> coordinator.resume()
            ACTION_RESET -> {
                coordinator.stop()
                finish()
            }
            else -> if (!foreground) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun observe() {
        observer?.cancel()
        val services = RunletServices.get()
        observer = scope.launch {
            services.coordinator.state.collect { state ->
                when (state.status) {
                    RunStatus.RUNNING -> {
                        holdWake()
                        update(state, services.notificationIcon)
                    }
                    RunStatus.PAUSED -> {
                        releaseWake()
                        update(state, services.notificationIcon)
                    }
                    RunStatus.COMPLETED, RunStatus.ERROR, RunStatus.IDLE -> finish()
                }
            }
        }
    }

    private fun update(state: RunState, icon: Int) {
        val notification = buildNotification(state, icon)
        if (foreground) {
            getSystemService(android.app.NotificationManager::class.java).notify(RUN_ID, notification)
        } else {
            enterForeground(notification)
        }
    }

    private fun buildNotification(state: RunState, icon: Int): Notification {
        val paused = state.status == RunStatus.PAUSED
        val builder = Notification.Builder(this, Channels.RUNNER)
            .setSmallIcon(icon)
            .setContentTitle("${state.automationName} · ${state.label}")
            .setContentText(
                if (paused) "Paused · ${formatClock(state.remainingMs)}"
                else "Running · action ${state.actionIndex + 1} of ${state.actionCount}",
            )
            .setContentIntent(openAppIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (!paused && state.deadlineMs > 0) {
            val leftMs = (state.deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            builder.setWhen(System.currentTimeMillis() + leftMs).setUsesChronometer(true).setChronometerCountDown(true)
        }
        builder.addAction(
            Notification.Action.Builder(null, if (paused) "Resume" else "Pause", control(if (paused) ACTION_RESUME else ACTION_PAUSE)).build(),
        )
        builder.addAction(Notification.Action.Builder(null, "Stop / Reset", control(ACTION_RESET)).build())
        return builder.build()
    }

    private fun placeholder(icon: Int): Notification =
        Notification.Builder(this, Channels.RUNNER).setSmallIcon(icon).setContentTitle("Starting routine").setOngoing(true).build()

    private fun enterForeground(notification: Notification) {
        startForeground(RUN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        foreground = true
    }

    private fun control(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, RunnerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun holdWake() {
        // Upper bound only; released as soon as the run pauses or ends.
        wake?.acquire(Limits.MAX_TOTAL_MS + WAKE_SLACK_MS)
    }

    private fun releaseWake() {
        wake?.takeIf { it.isHeld }?.release()
    }

    private fun finish() {
        observer?.cancel()
        releaseWake()
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_RUN = "dev.runlet.RUN"
        const val ACTION_PAUSE = "dev.runlet.PAUSE"
        const val ACTION_RESUME = "dev.runlet.RESUME"
        const val ACTION_RESET = "dev.runlet.RESET"
        const val EXTRA_AUTOMATION_ID = "automation_id"
        const val EXTRA_TRIGGERED = "triggered"
        private const val RUN_ID = 11
        private const val WAKE_SLACK_MS = 60_000L

        /** Starts the service and runs the saved automation [automationId]. */
        fun run(context: Context, automationId: Long, triggered: Boolean = false) {
            context.startForegroundService(
                Intent(context, RunnerService::class.java).setAction(ACTION_RUN)
                    .putExtra(EXTRA_AUTOMATION_ID, automationId).putExtra(EXTRA_TRIGGERED, triggered),
            )
        }
    }
}
