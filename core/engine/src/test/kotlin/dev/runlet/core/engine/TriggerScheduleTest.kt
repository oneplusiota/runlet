package dev.runlet.core.engine

import dev.runlet.core.model.Day
import dev.runlet.core.model.Trigger
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TriggerScheduleTest {
    private val utc = ZoneId.of("UTC")
    private fun at(s: String) = ZonedDateTime.parse("${s}[UTC]")

    @Test fun laterTodayWhenNotYetPassed() {
        // 2026-11-04 is a Wednesday
        assertEquals(at("2026-11-04T07:30:00Z"), TriggerSchedule.nextFire(Trigger.Time(7, 30), at("2026-11-04T06:00:00Z")))
    }

    @Test fun tomorrowWhenPassedOrExactlyNow() {
        assertEquals(at("2026-11-05T07:30:00Z"), TriggerSchedule.nextFire(Trigger.Time(7, 30), at("2026-11-04T07:30:00Z")))
        assertEquals(at("2026-11-05T07:30:00Z"), TriggerSchedule.nextFire(Trigger.Time(7, 30), at("2026-11-04T09:00:00Z")))
    }

    @Test fun skipsWeekendForWeekdays() {
        // Friday evening -> Monday
        assertEquals(at("2026-11-09T07:30:00Z"), TriggerSchedule.nextFire(Trigger.Time(7, 30, Day.WEEKDAYS), at("2026-11-06T20:00:00Z")))
    }

    @Test fun sameWeekdayNextWeekWhenOnlyTodayAndPassed() {
        assertEquals(at("2026-11-11T07:30:00Z"), TriggerSchedule.nextFire(Trigger.Time(7, 30, setOf(Day.WED)), at("2026-11-04T08:00:00Z")))
    }

    @Test fun noDaysNeverFires() = assertNull(TriggerSchedule.nextFire(Trigger.Time(7, 30, emptySet()), at("2026-11-04T08:00:00Z")))

    @Test fun dstGapStillFiresThatDay() {
        val ny = ZoneId.of("America/New_York") // 2026-03-08 02:30 does not exist
        val next = TriggerSchedule.nextFire(Trigger.Time(2, 30), ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny))!!
        assertEquals(java.time.LocalDate.of(2026, 3, 8), next.toLocalDate())
    }

    @Test fun previousFireIsAtOrBeforeNow() {
        assertEquals(at("2026-11-04T07:30:00Z"), TriggerSchedule.previousFire(Trigger.Time(7, 30), at("2026-11-04T07:30:00Z")))
        assertEquals(at("2026-11-03T07:30:00Z"), TriggerSchedule.previousFire(Trigger.Time(7, 30), at("2026-11-04T07:29:59Z")))
        // Monday morning looks back over the weekend to Friday
        assertEquals(at("2026-11-06T07:30:00Z"), TriggerSchedule.previousFire(Trigger.Time(7, 30, Day.WEEKDAYS), at("2026-11-09T06:00:00Z")))
        assertNull(TriggerSchedule.previousFire(Trigger.Time(7, 30, emptySet()), at("2026-11-04T08:00:00Z")))
    }

    @Test fun nextOfPicksEarliest() {
        val a = Trigger.Time(9, 0)
        val b = Trigger.Time(8, 0)
        assertEquals(b, TriggerSchedule.nextOf(listOf(a, Trigger.Boot, b), at("2026-11-04T06:00:00Z"))?.first)
    }
}
