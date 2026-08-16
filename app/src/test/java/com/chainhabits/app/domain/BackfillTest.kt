package com.chainhabits.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class BackfillTest {
    private val today = LocalDate.of(2026, 8, 12) // A Wednesday.

    private fun habit(
        cadence: Cadence = Cadence.Daily,
        createdOn: LocalDate = LocalDate.of(2026, 1, 1),
        archivedOn: LocalDate? = null,
    ) = Habit(
        id = 1,
        name = "Read",
        cadence = cadence,
        createdOn = createdOn,
        archivedOn = archivedOn,
    )

    @Test
    fun `window spans seven days including today`() {
        assertEquals(LocalDate.of(2026, 8, 6), Backfill.windowStart(today))
    }

    @Test
    fun `today and yesterday are editable`() {
        assertTrue(Backfill.isEditable(habit(), today, today))
        assertTrue(Backfill.isEditable(habit(), today.minusDays(1), today))
    }

    @Test
    fun `oldest day in the window is editable and the one before it is not`() {
        // The off-by-one that matters: seven days back inclusive is six days subtracted.
        assertTrue(Backfill.isEditable(habit(), today.minusDays(6), today))
        assertFalse(Backfill.isEditable(habit(), today.minusDays(7), today))
    }

    @Test
    fun `the future is not editable`() {
        assertFalse(Backfill.isEditable(habit(), today.plusDays(1), today))
    }

    @Test
    fun `days before the habit existed are not editable`() {
        val h = habit(createdOn = today.minusDays(2))
        assertTrue(Backfill.isEditable(h, today.minusDays(2), today))
        assertFalse(Backfill.isEditable(h, today.minusDays(3), today))
    }

    @Test
    fun `days from the archived day onward are not editable`() {
        val h = habit(archivedOn = today.minusDays(2))
        assertTrue(Backfill.isEditable(h, today.minusDays(3), today))
        assertFalse(Backfill.isEditable(h, today.minusDays(2), today))
        assertFalse(Backfill.isEditable(h, today.minusDays(1), today))
    }

    @Test
    fun `unscheduled weekdays are not editable`() {
        // Mon-Wed-Fri: writing a count against Tuesday would change nothing the evaluator
        // reads, so the sheet must not offer it.
        val h =
            habit(
                cadence =
                    Cadence.SpecificDays(
                        setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                    ),
            )
        assertTrue(Backfill.isEditable(h, today, today)) // Wednesday
        assertFalse(Backfill.isEditable(h, today.minusDays(1), today)) // Tuesday
        assertTrue(Backfill.isEditable(h, today.minusDays(2), today)) // Monday
    }

    @Test
    fun `every day of a times-per-week habit is editable inside the window`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        (0L..6L).forEach { back ->
            assertTrue("$back days back", Backfill.isEditable(h, today.minusDays(back), today))
        }
    }

    @Test
    fun `backfilling a forgotten day restores the streak it broke`() {
        // The whole point of the feature, checked through the evaluator rather than
        // asserted about the window in isolation.
        val h = habit(createdOn = today.minusDays(5))
        val logged = (0L..5L).filter { it != 2L }.map { Entry(1, today.minusDays(it), 1) }

        val before = HabitEvaluator.timeline(h, logged, today).stats
        assertEquals(2, before.currentStreak)

        val after =
            HabitEvaluator.timeline(h, logged + Entry(1, today.minusDays(2), 1), today).stats
        assertEquals(6, after.currentStreak)
    }
}
