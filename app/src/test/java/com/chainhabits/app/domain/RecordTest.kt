package com.chainhabits.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What counts as a personal best.
 *
 * The distinction under test is between [HabitStats.longestStreak], which includes the run
 * in progress, and [HabitStats.previousBest], which does not. Only the second can answer
 * "am I beating anything", and getting that wrong is what made every day of a record-holding
 * run look like a fresh record.
 */
class RecordTest {
    private val today = LocalDate.of(2026, 8, 12)
    private val start = today.minusDays(60)

    private fun habit(
        polarity: Polarity = Polarity.POSITIVE,
        strictness: Strictness = Strictness.STANDARD,
    ) = Habit(
        id = 1,
        name = "test",
        polarity = polarity,
        strictness = strictness,
        createdOn = start,
    )

    /** Every day from [from] through [to] inclusive. */
    private fun range(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    private fun statsFor(
        h: Habit,
        done: List<LocalDate>,
    ) = HabitEvaluator.timeline(h, done.map { Entry(1, it, 1) }, today).stats

    /** Logged every day except [skipped], which is what makes the runs. */
    private fun allDoneExcept(vararg skipped: LocalDate) =
        range(start, today).filter { it !in skipped }

    @Test
    fun `the run in progress is not the record it is chasing`() {
        // 61 days of history: a 25-day run (days 0..24), two misses, then days 27..60 live.
        val brokeOn = listOf(start.plusDays(25), start.plusDays(26))
        val stats = statsFor(habit(), allDoneExcept(*brokeOn.toTypedArray()))

        assertEquals(34, stats.currentStreak)
        assertEquals(34, stats.longestStreak)

        // The ended run was 25 days, and that is what the live 34 had to beat.
        assertEquals(25, stats.previousBest)
        assertTrue(stats.isRecord)
    }

    @Test
    fun `equalling the previous best is not a record`() {
        // A 20-day run, a two-day break, then exactly 20 more days up to today.
        val first = range(start, start.plusDays(19))
        val second = range(today.minusDays(19), today)
        val stats = statsFor(habit(), first + second)

        assertEquals(20, stats.currentStreak)
        assertEquals(20, stats.previousBest)

        // longestStreak cannot tell these apart - it equals currentStreak either way, which
        // is exactly why isRecord may not be built on it.
        assertEquals(20, stats.longestStreak)
        assertFalse(stats.isRecord)
    }

    @Test
    fun `one day past the previous best is a record`() {
        val first = range(start, start.plusDays(19))
        val second = range(today.minusDays(20), today)
        val stats = statsFor(habit(), first + second)

        assertEquals(21, stats.currentStreak)
        assertEquals(20, stats.previousBest)
        assertTrue(stats.isRecord)
    }

    @Test
    fun `a habit that has never broken a run has nothing to beat`() {
        val stats = statsFor(habit(), allDoneExcept())

        assertEquals(0, stats.previousBest)
        assertEquals(61, stats.longestStreak)
    }

    @Test
    fun `the first days of a brand new habit are not called its best ever`() {
        val fresh = habit().copy(createdOn = today.minusDays(1))
        val stats = HabitEvaluator.timeline(fresh, listOf(Entry(1, today, 1)), today).stats

        // Trivially the best it has ever done, and worth nothing. See RECORD_FLOOR.
        assertEquals(1, stats.currentStreak)
        assertEquals(0, stats.previousBest)
        assertFalse(stats.isRecord)
    }

    @Test
    fun `a run reaching the floor is a record even with no broken chain behind it`() {
        val fresh = habit().copy(createdOn = today.minusDays(2))
        val done = range(today.minusDays(2), today).map { Entry(1, it, 1) }
        val stats = HabitEvaluator.timeline(fresh, done, today).stats

        assertEquals(3, stats.currentStreak)
        assertTrue(stats.isRecord)
    }

    @Test
    fun `a broken run still counts toward the record once it has ended`() {
        // A 10-day run, then a break, then a shorter run that never catches it.
        val first = range(start, start.plusDays(9))
        val second = range(today.minusDays(4), today)
        val stats = statsFor(habit(), first + second)

        assertEquals(5, stats.currentStreak)
        assertEquals(10, stats.previousBest)
        assertEquals(10, stats.longestStreak)
        assertFalse(stats.isRecord)
    }
}
