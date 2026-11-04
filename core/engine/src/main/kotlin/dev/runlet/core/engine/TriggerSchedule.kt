package dev.runlet.core.engine

import dev.runlet.core.model.Day
import dev.runlet.core.model.Trigger
import java.time.DayOfWeek
import java.time.ZonedDateTime

/** Pure calendar math for time triggers, kept apart from AlarmManager so it can be tested. */
object TriggerSchedule {
    /** The first moment strictly after [after] that [trigger] fires, or null when it has no days. */
    fun nextFire(trigger: Trigger.Time, after: ZonedDateTime): ZonedDateTime? {
        if (trigger.days.isEmpty()) return null
        var date = after.toLocalDate()
        // Eight days always covers a full week plus today's already-passed slot.
        repeat(8) {
            if (day(date.dayOfWeek) in trigger.days) {
                // of() resolves a DST gap by moving forward, so the trigger still fires that day.
                val at = ZonedDateTime.of(date, java.time.LocalTime.of(trigger.hour, trigger.minute), after.zone)
                if (at.isAfter(after)) return at
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** The latest moment at or before [at] that [trigger] fired, or null when it has no days. Mirror of [nextFire]. */
    fun previousFire(trigger: Trigger.Time, at: ZonedDateTime): ZonedDateTime? {
        if (trigger.days.isEmpty()) return null
        var date = at.toLocalDate()
        repeat(8) {
            if (day(date.dayOfWeek) in trigger.days) {
                val fire = ZonedDateTime.of(date, java.time.LocalTime.of(trigger.hour, trigger.minute), at.zone)
                if (!fire.isAfter(at)) return fire
            }
            date = date.minusDays(1)
        }
        return null
    }

    /** Earliest next fire across all time triggers in [triggers], with the trigger that produced it. */
    fun nextOf(triggers: List<Trigger>, after: ZonedDateTime): Pair<Trigger.Time, ZonedDateTime>? =
        triggers.filterIsInstance<Trigger.Time>()
            .mapNotNull { t -> nextFire(t, after)?.let { t to it } }
            .minByOrNull { it.second.toInstant() }

    private fun day(d: DayOfWeek): Day = Day.entries[d.ordinal] // Day is declared Monday-first like DayOfWeek
}
