package com.chainhabits.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The write paths, against a real database.
 *
 * These are the rules the whole app depends on and none of them were covered: the domain
 * layer had forty tests and the layer that actually stores things had none. In-memory
 * Room rather than a fake DAO, because the behaviour under test *is* the SQL - conflict
 * strategies, cascades and transactions are exactly what a hand-written fake would get
 * wrong in the same direction as the code.
 */
@RunWith(AndroidJUnit4::class)
class HabitDaoTest {
    private lateinit var db: HabitDatabase
    private lateinit var dao: HabitDao
    private lateinit var repository: HabitRepository

    private val day: LocalDate = LocalDate.parse("2026-08-16")

    @Before
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    HabitDatabase::class.java,
                ).build()
        dao = db.habitDao()
        repository = HabitRepository(dao)
    }

    @After
    fun close() {
        db.close()
    }

    private fun habit(
        name: String = "Read",
        cadence: Cadence = Cadence.Daily,
        polarity: Polarity = Polarity.POSITIVE,
    ) = Habit(
        name = name,
        polarity = polarity,
        cadence = cadence,
        createdOn = day.minusMonths(1),
    )

    // --- logging ---

    @Test
    fun dailyHabitTogglesOff() =
        runBlocking {
            val stored = repository.getHabit(repository.addHabit(habit()))!!

            repository.logEvent(stored, day)
            assertEquals(1, dao.getEntry(stored.id, day)?.count)

            // Tapping an already-done daily habit undoes it.
            repository.logEvent(stored, day)
            assertNull("a second tap must clear the day", dao.getEntry(stored.id, day))
        }

    @Test
    fun timesPerWeekHabitAccumulatesInsteadOfToggling() =
        runBlocking {
            val stored =
                repository.getHabit(repository.addHabit(habit(cadence = Cadence.TimesPerWeek(3))))!!

            repeat(4) { repository.logEvent(stored, day) }

            // A fourth workout in a 3x week is a fourth workout, not an undo of the third.
            assertEquals(4, dao.getEntry(stored.id, day)?.count)
        }

    @Test
    fun removeEventStepsDownAndDeletesAtZero() =
        runBlocking {
            val stored =
                repository.getHabit(repository.addHabit(habit(cadence = Cadence.TimesPerWeek(3))))!!
            repeat(2) { repository.logEvent(stored, day) }

            repository.removeEvent(stored, day)
            assertEquals(1, dao.getEntry(stored.id, day)?.count)

            repository.removeEvent(stored, day)
            assertNull("the row must go rather than sit at zero", dao.getEntry(stored.id, day))
        }

    @Test
    fun removeEventOnAnUnloggedDayDoesNotStoreANegative() =
        runBlocking {
            val stored = repository.getHabit(repository.addHabit(habit()))!!
            repository.removeEvent(stored, day)
            assertNull(dao.getEntry(stored.id, day))
        }

    @Test
    fun setEventCountWritesTheNumberAskedForWhateverTheCadence() =
        runBlocking {
            // Unlike logEvent, this must not toggle or accumulate - the backfill sheet is
            // editing a day it is already showing the user.
            val daily = repository.getHabit(repository.addHabit(habit()))!!
            repository.setEventCount(daily, day, 1)
            repository.setEventCount(daily, day, 1)
            assertEquals(1, dao.getEntry(daily.id, day)?.count)

            repository.setEventCount(daily, day, 0)
            assertNull(dao.getEntry(daily.id, day))
        }

    @Test
    fun setEventCountTreatsNegativesAsClearing() =
        runBlocking {
            val stored = repository.getHabit(repository.addHabit(habit()))!!
            repository.logEvent(stored, day)
            repository.setEventCount(stored, day, -3)
            assertNull(dao.getEntry(stored.id, day))
        }

    // --- ordering ---

    @Test
    fun newHabitsAreAppendedRatherThanTied() =
        runBlocking {
            repository.addHabit(habit("First"))
            repository.addHabit(habit("Second"))
            repository.addHabit(habit("Third"))

            assertEquals(
                listOf("First", "Second", "Third"),
                repository.observeHabits().first().map { it.name },
            )
        }

    @Test
    fun aNewHabitLandsAtTheEndOfAReorderedList() =
        runBlocking {
            val a = repository.addHabit(habit("A"))
            val b = repository.addHabit(habit("B"))
            repository.applyOrder(listOf(b, a))

            repository.addHabit(habit("C"))

            // The regression: C used to take sortOrder 0 and surface just under B.
            assertEquals(
                listOf("B", "A", "C"),
                repository.observeHabits().first().map { it.name },
            )
        }

    @Test
    fun archivedHabitsLeaveTheListButKeepTheirNumbering() =
        runBlocking {
            val a = repository.getHabit(repository.addHabit(habit("A")))!!
            repository.addHabit(habit("B"))
            repository.archiveHabit(a, day)

            assertEquals(listOf("B"), repository.observeHabits().first().map { it.name })
            // A still holds sortOrder 0, so the next habit must not reuse it.
            assertEquals(2, nextSortOrder(dao.allHabits()))
        }

    // --- pauses ---

    @Test
    fun pausingTwiceLeavesOneOpenPause() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.pauseHabit(id, day)
            repository.pauseHabit(id, day.plusDays(1))

            assertEquals(1, dao.observePausesFor(id).first().size)
            // The original start survives: a habit paused since last week keeps that date.
            assertEquals(
                day,
                dao
                    .observePausesFor(id)
                    .first()
                    .single()
                    .start,
            )
        }

    /**
     * The mis-tap: paused and un-paused within the same minute.
     *
     * Storing that as a pause ending today would leave the day dashed out and dropped from
     * the streak until midnight - the habit still reading as paused for hours after the
     * user un-paused it, which is what this looked like on a real phone. A pause covering
     * no day is not history, so the row goes.
     */
    @Test
    fun pausingAndResumingTheSameDayLeavesNothingBehind() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.pauseHabit(id, day)

            repository.resumeHabit(id, day)

            assertEquals(0, dao.observePausesFor(id).first().size)
        }

    /** A resume dated before the pause began covers nothing either, so it also collapses. */
    @Test
    fun resumingBeforeThePauseBeganLeavesNothingBehind() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.pauseHabit(id, day)

            repository.resumeHabit(id, day.minusDays(3))

            assertEquals(0, dao.observePausesFor(id).first().size)
        }

    /**
     * Resuming makes the habit live *from* the resume day, so the pause closes the day
     * before. The day you come back is a day you are being judged on again.
     */
    @Test
    fun resumingClosesThePauseOnTheDayBefore() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.pauseHabit(id, day.minusDays(5))

            repository.resumeHabit(id, day)

            val pause = dao.observePausesFor(id).first().single()
            assertEquals(day.minusDays(5), pause.start)
            assertEquals(day.minusDays(1), pause.end)
        }

    @Test
    fun aPauseKeepsTheReasonItWasGiven() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.pauseHabit(id, day, "On vacation")

            assertEquals(
                "On vacation",
                dao
                    .observePausesFor(id)
                    .first()
                    .single()
                    .reason,
            )
        }

    /** Editing the message says something new about the same break, not a new break. */
    @Test
    fun pausingAgainRewritesTheReasonWithoutRestartingThePause() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.pauseHabit(id, day.minusDays(5), "On vacation")

            repository.pauseHabit(id, day, "Injured leg")

            val pause = dao.observePausesFor(id).first().single()
            assertEquals(day.minusDays(5), pause.start)
            assertEquals("Injured leg", pause.reason)
        }

    @Test
    fun resumingWithoutAPauseDoesNothing() =
        runBlocking {
            val id = repository.addHabit(habit())
            repository.resumeHabit(id, day)
            assertEquals(0, dao.observePausesFor(id).first().size)
        }

    /**
     * Pausing more habits under a new message must not retitle a break already running.
     *
     * The picker has one message field for the whole selection, so without this, pausing
     * two habits "On vacation" silently rewrote the habit that had been paused for a
     * fortnight with "Injured leg".
     */
    @Test
    fun anUneditedReasonLeavesARunningPauseAlone() =
        runBlocking {
            val a = repository.addHabit(habit("A"))
            val b = repository.addHabit(habit("B"))
            repository.pauseHabit(a, day.minusDays(5), "Injured leg")

            repository.setPaused(
                pause = listOf(a, b),
                resume = emptyList(),
                on = day,
                reason = "On vacation",
                rewriteReason = false,
            )

            assertEquals("Injured leg", dao.openPauseFor(a)!!.reason)
            assertEquals("On vacation", dao.openPauseFor(b)!!.reason)
        }

    @Test
    fun applyingASelectionSkipsHabitsAlreadyPaused() =
        runBlocking {
            val a = repository.addHabit(habit("A"))
            val b = repository.addHabit(habit("B"))
            repository.pauseHabit(a, day.minusDays(5))

            repository.setPaused(pause = listOf(a, b), resume = emptyList(), on = day)

            // A keeps its real start date rather than being restarted today.
            assertEquals(
                day.minusDays(5),
                dao
                    .observePausesFor(a)
                    .first()
                    .single()
                    .start,
            )
            assertEquals(2, dao.pausedHabitIds().size)
        }

    /** The picker's answer is a whole selection, so one call moves habits both ways. */
    @Test
    fun applyingASelectionPausesAndResumesInOneGo() =
        runBlocking {
            val a = repository.addHabit(habit("A"))
            val b = repository.addHabit(habit("B"))
            repository.pauseHabit(a, day.minusDays(5))

            repository.setPaused(
                pause = listOf(b),
                resume = listOf(a),
                on = day,
                reason = "Injured leg",
            )

            assertEquals(listOf(b), dao.pausedHabitIds())
            assertEquals(
                "Injured leg",
                dao
                    .observePausesFor(b)
                    .first()
                    .single()
                    .reason,
            )
        }

    // --- deletion ---

    @Test
    fun deletingAHabitTakesItsEntriesAndPausesWithIt() =
        runBlocking {
            val stored = repository.getHabit(repository.addHabit(habit()))!!
            repository.logEvent(stored, day)
            repository.pauseHabit(stored.id, day)

            repository.deleteHabit(stored)

            // The cascade is declared on the entity; this is what proves it is real.
            assertEquals(0, dao.allEntries().size)
            assertEquals(0, dao.allPauses().size)
        }

    // --- restore ---

    @Test
    fun restoreReplacesEverythingInOneTransaction() =
        runBlocking {
            val old = repository.getHabit(repository.addHabit(habit("Old")))!!
            repository.logEvent(old, day)

            val text =
                buildBackup(
                    habits = listOf(habit("New").toEntity().copy(id = 55, sortOrder = 2)),
                    entries = listOf(EntryEntity(55, day, 3)),
                    pauses = emptyList(),
                    exportedAt = "2026-08-16T00:00:00Z",
                )
            val result = repository.restoreBackup(text)

            assertEquals(RestoreResult.Success::class.java, result::class.java)
            assertEquals(listOf("New"), repository.observeHabits().first().map { it.name })
            // Ids are preserved, so the entry still points at its habit.
            assertEquals(3, dao.getEntry(55, day)?.count)
            assertNull("the replaced habit must be gone", repository.getHabit(old.id))
        }

    @Test
    fun aRefusedBackupLeavesTheExistingHistoryUntouched() =
        runBlocking {
            val old = repository.getHabit(repository.addHabit(habit("Old")))!!
            repository.logEvent(old, day)

            val result = repository.restoreBackup("not a backup at all")

            assertEquals(RestoreResult.Failure::class.java, result::class.java)
            assertNotNull(repository.getHabit(old.id))
            assertEquals(1, dao.getEntry(old.id, day)?.count)
        }
}
