package dev.runlet.core.platform

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.runlet.core.data.AutomationRepository
import dev.runlet.core.data.RunletDatabase
import dev.runlet.core.engine.Clock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TriggerSchedulerTest {
    private val utc = ZoneId.of("UTC")
    private lateinit var db: RunletDatabase
    private lateinit var automations: AutomationRepository
    private lateinit var context: Context
    private var now = 0L
    private var lastRun: Long? = null
    private val grace = TriggerReceiver.MISSED_GRACE_MS

    private fun ms(s: String) = ZonedDateTime.parse("$s[UTC]").toInstant().toEpochMilli()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, RunletDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        automations = AutomationRepository(db.automations(), Clock { 0 })
    }

    @After fun tearDown() = db.close()

    private fun scheduler() = TriggerScheduler(context, automations, nowMs = { now }, zone = { utc }, lastRunMs = { lastRun })

    private fun armed(trigger: String): Long = runBlocking {
        val id = automations.save(null, "routine \"T\"\n$trigger\nchime\n")
        automations.setEnabled(id, true)
        id
    }

    @Test fun runsATriggerMissedJustBeforeBoot() {
        val id = armed("when time 07:30")
        now = ms("2026-11-04T07:40:00Z")
        assertEquals(listOf(id), runBlocking { scheduler().missedWithin(grace) })
    }

    @Test fun dropsOldMisses() {
        armed("when time 07:30")
        now = ms("2026-11-04T07:46:00Z")
        assertEquals(emptyList(), runBlocking { scheduler().missedWithin(grace) })
    }

    @Test fun doesNotRunTwiceWhenItAlreadyRanOnTime() {
        armed("when time 07:30")
        now = ms("2026-11-04T07:35:00Z")
        lastRun = ms("2026-11-04T07:30:01Z")
        assertEquals(emptyList(), runBlocking { scheduler().missedWithin(grace) })
    }

    @Test fun yesterdaysRunDoesNotCountAsToday() {
        val id = armed("when time 07:30")
        now = ms("2026-11-04T07:35:00Z")
        lastRun = ms("2026-11-03T07:30:01Z")
        assertEquals(listOf(id), runBlocking { scheduler().missedWithin(grace) })
    }

    @Test fun respectsDaysAndDisabledRoutines() {
        val weekdays = armed("when time 07:30 weekdays")
        now = ms("2026-11-07T07:40:00Z") // Saturday: nothing was due
        assertEquals(emptyList(), runBlocking { scheduler().missedWithin(grace) })
        now = ms("2026-11-06T07:40:00Z") // Friday
        assertEquals(listOf(weekdays), runBlocking { scheduler().missedWithin(grace) })
        runBlocking { automations.setEnabled(weekdays, false) }
        assertEquals(emptyList(), runBlocking { scheduler().missedWithin(grace) })
    }
}
