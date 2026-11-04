package dev.runlet.core.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.engine.ScriptParser
import dev.runlet.core.engine.TriggerSchedule
import dev.runlet.core.model.Trigger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Arms one alarm for the earliest upcoming time trigger across all enabled automations. When it
 * fires, [TriggerReceiver] asks [dueAt] which automations to run and then re-arms. One alarm
 * instead of one per trigger means there is never a stale alarm to cancel.
 */
class TriggerScheduler(
    private val context: Context,
    private val automations: AutomationRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** When an automation last started a run, so a missed trigger is not run twice. */
    private val lastRunMs: suspend (Long) -> Long? = { null },
) {
    private val alarms get() = context.getSystemService(AlarmManager::class.java)

    private data class Armed(val id: Long, val triggers: List<Trigger>)

    private suspend fun armed(): List<Armed> = automations.enabled().mapNotNull { row ->
        runCatching { ScriptParser.parseOutline(row.source).triggers }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { Armed(row.id, it) }
    }

    /** Recomputes and sets the alarm, or clears it when nothing is due in future. */
    suspend fun reschedule() {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs()), zone())
        val next = armed().mapNotNull { TriggerSchedule.nextOf(it.triggers, now)?.second }
            .minByOrNull { it.toInstant() }
        val pending = pending(next?.toInstant()?.toEpochMilli() ?: 0L)
        if (next == null) {
            alarms.cancel(pending)
            return
        }
        val at = next.toInstant().toEpochMilli()
        // Exact alarms need a user-granted permission on Android 12+; without it the alarm may be a few minutes late.
        if (canScheduleExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Ids of automations with a time trigger that fires exactly at [targetMs]. */
    suspend fun dueAt(targetMs: Long): List<Long> {
        val justBefore = ZonedDateTime.ofInstant(Instant.ofEpochMilli(targetMs - 1), zone())
        return armed().filter { a ->
            a.triggers.filterIsInstance<Trigger.Time>().any {
                TriggerSchedule.nextFire(it, justBefore)?.toInstant()?.toEpochMilli() == targetMs
            }
        }.map { it.id }
    }

    /**
     * Ids of automations whose time trigger came due within the last [graceMs] without a run since,
     * which is what a phone that was off at the time looks like. Older misses are dropped on purpose:
     * a 07:30 routine at 14:00 is more surprising than not running.
     */
    suspend fun missedWithin(graceMs: Long): List<Long> {
        val now = nowMs()
        val at = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone())
        return armed().filter { a ->
            a.triggers.filterIsInstance<Trigger.Time>().any { t ->
                val due = TriggerSchedule.previousFire(t, at)?.toInstant()?.toEpochMilli() ?: return@any false
                now - due <= graceMs && (lastRunMs(a.id) ?: Long.MIN_VALUE) < due
            }
        }.map { it.id }
    }

    /** Ids of automations with a `when boot` trigger. */
    suspend fun bootIds(): List<Long> = armed().filter { Trigger.Boot in it.triggers }.map { it.id }

    /** Always true before Android 12, where exact alarms need no special permission. */
    fun canScheduleExact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun pending(targetMs: Long): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_FIRE)
            .putExtra(TriggerReceiver.EXTRA_TARGET_MS, targetMs),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
