package dev.runlet.core.platform

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Delivers the trigger alarm, and re-arms it after anything that can invalidate it. */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = RunletServices.get().triggers
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> {
                        val target = intent.getLongExtra(EXTRA_TARGET_MS, 0L)
                        if (target > 0) scheduler.dueAt(target).forEach { RunnerService.run(context, it, triggered = true) }
                        scheduler.reschedule()
                    }
                    Intent.ACTION_BOOT_COMPLETED -> {
                        // Boot runs, then anything a powered-off phone missed in the last few minutes.
                        (scheduler.bootIds() + scheduler.missedWithin(MISSED_GRACE_MS)).distinct()
                            .forEach { RunnerService.run(context, it, triggered = true) }
                        scheduler.reschedule()
                    }
                    Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED,
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> scheduler.reschedule()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.runlet.TRIGGER_FIRE"
        const val EXTRA_TARGET_MS = "target_ms"
        const val MISSED_GRACE_MS = 15 * 60_000L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
