package com.tsfeith.habits.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HabitEvaluatorTest {

    // A Wednesday, so week boundaries are unambiguous in the weekly tests.
    private val today = LocalDate.of(2026, 8, 12)
    private val start = today.minusDays(30)

    private fun habit(
        polarity: Polarity = Polarity.POSITIVE,
        strictness: Strictness = Strictness.STANDARD,
        cadence: Cadence = Cadence.Daily,
        createdOn: LocalDate = start,
    ) = Habit(
        id = 1,
        name = "test",
        polarity = polarity,
        strictness = strictness,
        cadence = cadence,
        createdOn = createdOn,
    )

    private fun entries(vararg days: LocalDate) = days.map { Entry(1, it, 1) }

    private fun cellOn(cells: List<Cell>, date: LocalDate) =
        cells.first { it.date == date }.state

    /** Every day from [from] through [to] inclusive. */
    private fun range(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    // --- Positive daily habits ---------------------------------------------------

    @Test
    fun `completing a positive habit today settles it immediately`() {
        val h = habit()
        val cells = HabitEvaluator.cells(h, entries(today), today.minusDays(3), today)
        assertEquals(CellState.DONE, cellOn(cells, today))
    }

    @Test
    fun `an untouched positive habit today stays pending, not missed`() {
        val h = habit()
        val cells = HabitEvaluator.cells(h, emptyList(), today.minusDays(3), today)
        assertEquals(CellState.PENDING, cellOn(cells, today))
    }

    @Test
    fun `an untouched past day on a positive habit rolls over to a miss`() {
        val h = habit()
        val done = range(start, today).filter { it != today.minusDays(1) }
        val cells = HabitEvaluator.cells(h, entries(*done.toTypedArray()), start, today)
        assertEquals(CellState.MISSED_ONCE, cellOn(cells, today.minusDays(1)))
    }

    // --- Never miss twice --------------------------------------------------------

    @Test
    fun `one miss is amber and leaves the chain intact`() {
        val h = habit()
        val missed = today.minusDays(3)
        val done = range(start, today).filter { it != missed }
        val stats = HabitEvaluator.stats(h, entries(*done.toTypedArray()), today)

        // Reset by the miss, then three good days since: today, -1 and -2.
        assertEquals(3, stats.currentStreak)
        assertTrue("chain should survive a single miss", stats.chainLength > 3)
        assertFalse("no longer at risk once you resume", stats.atRisk)
    }

    @Test
    fun `a second consecutive miss breaks the chain`() {
        val h = habit()
        val missed = setOf(today.minusDays(3), today.minusDays(2))
        val done = range(start, today).filter { it !in missed }
        val cells = HabitEvaluator.cells(h, entries(*done.toTypedArray()), start, today)

        assertEquals(CellState.MISSED_ONCE, cellOn(cells, today.minusDays(3)))
        assertEquals(CellState.BROKEN, cellOn(cells, today.minusDays(2)))
    }

    @Test
    fun `a single miss yesterday puts the habit at risk`() {
        val h = habit()
        // Today deliberately left undone: once you do it today you are no longer at
        // risk of missing twice, so the banner has to be driven by the open day.
        val skip = setOf(today, today.minusDays(1))
        val done = range(start, today).filter { it !in skip }
        val stats = HabitEvaluator.stats(h, entries(*done.toTypedArray()), today)
        assertTrue(stats.atRisk)
    }

    @Test
    fun `doing it today clears the risk`() {
        val h = habit()
        val done = range(start, today).filter { it != today.minusDays(1) }
        val stats = HabitEvaluator.stats(h, entries(*done.toTypedArray()), today)
        assertFalse("you did not miss twice, so nothing is at risk", stats.atRisk)
    }

    @Test
    fun `strict habits break on the first miss and are never merely at risk`() {
        val h = habit(strictness = Strictness.STRICT)
        val done = range(start, today).filter { it != today.minusDays(1) }
        val cells = HabitEvaluator.cells(h, entries(*done.toTypedArray()), start, today)
        val stats = HabitEvaluator.stats(h, entries(*done.toTypedArray()), today)

        assertEquals(CellState.BROKEN, cellOn(cells, today.minusDays(1)))
        assertFalse("a strict miss has already broken, not put at risk", stats.atRisk)
        // The chain restarted at the break, so it counts only today.
        assertEquals(1, stats.chainLength)
    }

    // --- Negative habits ---------------------------------------------------------

    @Test
    fun `an untouched past day on a negative habit rolls over to a success`() {
        val h = habit(polarity = Polarity.NEGATIVE)
        val cells = HabitEvaluator.cells(h, emptyList(), start, today)
        assertEquals(CellState.DONE, cellOn(cells, today.minusDays(1)))
    }

    @Test
    fun `logging a slip on a negative habit marks that day immediately`() {
        val h = habit(polarity = Polarity.NEGATIVE)
        val cells = HabitEvaluator.cells(h, entries(today), start, today)
        // Settled, not left pending - but on a standard habit a first slip is still
        // only amber. It takes a second to break the chain.
        assertEquals(CellState.MISSED_ONCE, cellOn(cells, today))
    }

    @Test
    fun `a clean today counts toward days since last slip`() {
        val h = habit(polarity = Polarity.NEGATIVE, strictness = Strictness.STRICT)
        val slip = today.minusDays(5)
        val stats = HabitEvaluator.stats(h, entries(slip), today)
        // The 4 clean settled days plus today, which is provisionally clean.
        assertEquals(5, stats.currentStreak)
    }

    @Test
    fun `a strict negative habit breaks on a single slip`() {
        val h = habit(polarity = Polarity.NEGATIVE, strictness = Strictness.STRICT)
        val cells = HabitEvaluator.cells(h, entries(today.minusDays(4)), start, today)
        assertEquals(CellState.BROKEN, cellOn(cells, today.minusDays(4)))
    }

    // --- Specific days -----------------------------------------------------------

    @Test
    fun `unscheduled days are not failures`() {
        val h = habit(cadence = Cadence.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val cells = HabitEvaluator.cells(h, emptyList(), start, today)
        val wednesday = cells.first { it.date.dayOfWeek == DayOfWeek.WEDNESDAY }
        assertEquals(CellState.NOT_SCHEDULED, wednesday.state)
    }

    @Test
    fun `days before the habit existed are not failures`() {
        val h = habit(createdOn = today.minusDays(3))
        val cells = HabitEvaluator.cells(h, emptyList(), start, today)
        assertEquals(CellState.NOT_SCHEDULED, cellOn(cells, today.minusDays(10)))
    }

    // --- Times per week ----------------------------------------------------------

    @Test
    fun `weekly cells are weeks, not days`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val cells = HabitEvaluator.cells(h, emptyList(), start, today)
        assertTrue(cells.all { it.date.dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test
    fun `partial progress this week leaves the week pending`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val monday = HabitEvaluator.weekStart(today)
        val cells = HabitEvaluator.cells(h, entries(monday, monday.plusDays(1)), start, today)
        val current = cells.last()

        assertEquals(CellState.PENDING, current.state)
        assertEquals("drives the '2 of 3 this week' readout", 2, current.count)
    }

    @Test
    fun `hitting quota settles the week immediately`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val monday = HabitEvaluator.weekStart(today)
        val done = entries(monday, monday.plusDays(1), monday.plusDays(2))
        val cells = HabitEvaluator.cells(h, done, start, today)
        assertEquals(CellState.DONE, cells.last().state)
    }

    @Test
    fun `a past week under quota is a miss`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)

        // Earlier weeks have to hit quota, or the run of misses breaks the chain before
        // it ever reaches the week under test.
        val earlier = (2..4).flatMap { w ->
            val monday = lastMonday.minusWeeks((w - 1).toLong())
            listOf(monday, monday.plusDays(1), monday.plusDays(2))
        }
        val cells = HabitEvaluator.cells(
            h,
            entries(*(earlier + lastMonday).toTypedArray()),
            start,
            today,
        )
        assertEquals(CellState.MISSED_ONCE, cellOn(cells, lastMonday))
    }

    @Test
    fun `a once per week habit is just a target of one`() {
        val h = habit(cadence = Cadence.TimesPerWeek(1))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)
        val cells = HabitEvaluator.cells(h, entries(lastMonday.plusDays(4)), start, today)

        assertEquals(CellState.DONE, cellOn(cells, lastMonday))
        assertFalse("target of 1 shows a check, not a one-pip row", h.showsQuotaPips)
    }

    @Test
    fun `a negative weekly target is an allowance, not a floor`() {
        // "Eat out at most 2x per week".
        val h = habit(polarity = Polarity.NEGATIVE, cadence = Cadence.TimesPerWeek(2))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)

        val withinAllowance = entries(lastMonday, lastMonday.plusDays(2))
        assertEquals(
            CellState.DONE,
            cellOn(HabitEvaluator.cells(h, withinAllowance, start, today), lastMonday),
        )

        val overAllowance = withinAllowance + entries(lastMonday.plusDays(4))
        assertEquals(
            CellState.MISSED_ONCE,
            cellOn(HabitEvaluator.cells(h, overAllowance, start, today), lastMonday),
        )
    }

    @Test
    fun `weekly streaks are counted in weeks`() {
        val h = habit(cadence = Cadence.TimesPerWeek(1))
        val thisMonday = HabitEvaluator.weekStart(today)
        val done = (0..3).map { thisMonday.minusWeeks(it.toLong()) }
        val stats = HabitEvaluator.stats(h, entries(*done.toTypedArray()), today)
        assertEquals(4, stats.currentStreak)
    }

    // --- Rates -------------------------------------------------------------------

    @Test
    fun `completion rate ignores pending and unscheduled periods`() {
        val h = habit(cadence = Cadence.SpecificDays(setOf(DayOfWeek.MONDAY)))
        val mondays = range(start, today).filter { it.dayOfWeek == DayOfWeek.MONDAY }
        val done = mondays.drop(1) // missed the first one
        val stats = HabitEvaluator.stats(h, entries(*done.toTypedArray()), today)

        assertEquals(done.size.toFloat() / mondays.size, stats.completionRate, 0.001f)
    }
}
