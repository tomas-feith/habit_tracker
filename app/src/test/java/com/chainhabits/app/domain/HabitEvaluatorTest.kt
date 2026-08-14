package com.chainhabits.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun timelineOf(
        h: Habit,
        entries: List<Entry>,
    ) = HabitEvaluator.timeline(h, entries, today)

    private fun stateOn(
        h: Habit,
        entries: List<Entry>,
        date: LocalDate,
    ) = timelineOf(h, entries).cells.first { it.date == date }.state

    /** Every day from [from] through [to] inclusive. */
    private fun range(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    private fun allDoneExcept(vararg skipped: LocalDate) =
        entries(*range(start, today).filter { it !in skipped }.toTypedArray())

    // --- Positive daily habits ---------------------------------------------------

    @Test
    fun `completing a positive habit today settles it immediately`() {
        assertEquals(CellState.DONE, stateOn(habit(), entries(today), today))
    }

    @Test
    fun `an untouched positive habit today stays pending, not missed`() {
        assertEquals(CellState.PENDING, stateOn(habit(), emptyList(), today))
    }

    @Test
    fun `an untouched past day on a positive habit rolls over to a miss`() {
        val missed = today.minusDays(1)
        assertEquals(CellState.MISSED_ONCE, stateOn(habit(), allDoneExcept(missed), missed))
    }

    // --- Never miss twice --------------------------------------------------------

    @Test
    fun `one miss is amber and leaves the chain intact`() {
        val stats = timelineOf(habit(), allDoneExcept(today.minusDays(3))).stats

        // Reset by the miss, then three good days since: today, -1 and -2.
        assertEquals(3, stats.currentStreak)
        assertTrue("chain should survive a single miss", stats.chainLength > 3)
        assertFalse("no longer at risk once you resume", stats.atRisk)
    }

    @Test
    fun `a second consecutive miss breaks the chain`() {
        val h = habit()
        val e = allDoneExcept(today.minusDays(3), today.minusDays(2))

        assertEquals(CellState.MISSED_ONCE, stateOn(h, e, today.minusDays(3)))
        assertEquals(CellState.BROKEN, stateOn(h, e, today.minusDays(2)))
    }

    @Test
    fun `a single miss yesterday puts the habit at risk`() {
        // Today deliberately left undone: once you do it today you are no longer at risk
        // of missing twice, so the banner has to be driven by the open day.
        val stats = timelineOf(habit(), allDoneExcept(today, today.minusDays(1))).stats
        assertTrue(stats.atRisk)
    }

    @Test
    fun `doing it today clears the risk`() {
        val stats = timelineOf(habit(), allDoneExcept(today.minusDays(1))).stats
        assertFalse("you did not miss twice, so nothing is at risk", stats.atRisk)
    }

    @Test
    fun `strict habits break on the first miss and are never merely at risk`() {
        val h = habit(strictness = Strictness.STRICT)
        val e = allDoneExcept(today.minusDays(1))

        assertEquals(CellState.BROKEN, stateOn(h, e, today.minusDays(1)))
        assertFalse(
            "a strict miss has already broken, not put at risk",
            timelineOf(h, e).stats.atRisk,
        )
        // The chain restarted at the break, so it counts only today.
        assertEquals(1, timelineOf(h, e).stats.chainLength)
    }

    /** Regression: the window slice must not restart the never-miss-twice counter. */
    @Test
    fun `a break before the visible window still reads as broken inside it`() {
        val h = habit()
        // Two misses back to back, the first of them outside the four-week strip.
        val e = allDoneExcept(today.minusDays(28), today.minusDays(27))
        val windowed = timelineOf(h, e).since(HabitEvaluator.inlineWindowStart(h, today))

        assertEquals(
            "the second miss is still the second, whatever the window shows",
            CellState.BROKEN,
            windowed.first { it.date == today.minusDays(27) }.state,
        )
    }

    // --- Negative habits ---------------------------------------------------------

    @Test
    fun `an untouched past day on a negative habit rolls over to a success`() {
        val h = habit(polarity = Polarity.NEGATIVE)
        assertEquals(CellState.DONE, stateOn(h, emptyList(), today.minusDays(1)))
    }

    @Test
    fun `logging a slip on a negative habit marks that day immediately`() {
        // Settled, not left pending - but on a standard habit a first slip is still only
        // amber. It takes a second to break the chain.
        val h = habit(polarity = Polarity.NEGATIVE)
        assertEquals(CellState.MISSED_ONCE, stateOn(h, entries(today), today))
    }

    @Test
    fun `a clean today counts toward days since last slip`() {
        val h = habit(polarity = Polarity.NEGATIVE, strictness = Strictness.STRICT)
        // The 4 clean settled days plus today, which is provisionally clean.
        assertEquals(5, timelineOf(h, entries(today.minusDays(5))).stats.currentStreak)
    }

    @Test
    fun `a strict negative habit breaks on a single slip`() {
        val h = habit(polarity = Polarity.NEGATIVE, strictness = Strictness.STRICT)
        assertEquals(
            CellState.BROKEN,
            stateOn(h, entries(today.minusDays(4)), today.minusDays(4)),
        )
    }

    @Test
    fun `a daily negative habit tolerates nothing`() {
        val h = habit(polarity = Polarity.NEGATIVE)
        assertEquals(
            CellState.MISSED_ONCE,
            stateOn(h, entries(today.minusDays(2)), today.minusDays(2)),
        )
    }

    // --- Specific days -----------------------------------------------------------

    @Test
    fun `unscheduled days are not failures`() {
        val h = habit(cadence = Cadence.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        val wednesday =
            timelineOf(h, emptyList())
                .cells
                .first { it.date.dayOfWeek == DayOfWeek.WEDNESDAY }
        assertEquals(CellState.NOT_SCHEDULED, wednesday.state)
    }

    @Test
    fun `history starts at creation, not before`() {
        val h = habit(createdOn = today.minusDays(3))
        val cells = timelineOf(h, emptyList()).cells
        assertEquals(today.minusDays(3), cells.first().date)
        assertEquals(4, cells.size)
    }

    // --- Times per week ----------------------------------------------------------

    @Test
    fun `weekly cells are weeks, not days`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        assertTrue(timelineOf(h, emptyList()).cells.all { it.date.dayOfWeek == DayOfWeek.MONDAY })
    }

    @Test
    fun `partial progress this week leaves the week pending`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val monday = HabitEvaluator.weekStart(today)
        val timeline = timelineOf(h, entries(monday, monday.plusDays(1)))

        assertEquals(CellState.PENDING, timeline.current?.state)
        assertEquals("drives the '2 of 3 this week' readout", 2, timeline.currentCount)
    }

    @Test
    fun `hitting quota settles the week immediately`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val monday = HabitEvaluator.weekStart(today)
        val done = entries(monday, monday.plusDays(1), monday.plusDays(2))
        assertEquals(CellState.DONE, timelineOf(h, done).current?.state)
    }

    @Test
    fun `a past week under quota is a miss`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)

        // Earlier weeks have to hit quota, or the run of misses breaks the chain before
        // it ever reaches the week under test.
        val earlier =
            (1..2).flatMap { w ->
                val monday = lastMonday.minusWeeks(w.toLong())
                listOf(monday, monday.plusDays(1), monday.plusDays(2))
            }
        assertEquals(
            CellState.MISSED_ONCE,
            stateOn(h, entries(*(earlier + lastMonday).toTypedArray()), lastMonday),
        )
    }

    /** Regression: a habit created mid-week must not open with an unearned miss. */
    @Test
    fun `the creation week is not judged against a full quota`() {
        // Created on a Thursday, so only four days of that week were ever available.
        val createdOn = HabitEvaluator.weekStart(today).minusWeeks(2).plusDays(3)
        val h = habit(cadence = Cadence.TimesPerWeek(3), createdOn = createdOn)

        val creationWeek = HabitEvaluator.weekStart(createdOn)
        assertEquals(
            CellState.NOT_SCHEDULED,
            stateOn(h, entries(createdOn), creationWeek),
        )
    }

    @Test
    fun `a full creation week is judged normally`() {
        val createdOn = HabitEvaluator.weekStart(today).minusWeeks(2)
        val h = habit(cadence = Cadence.TimesPerWeek(3), createdOn = createdOn)
        assertNotEquals(
            CellState.NOT_SCHEDULED,
            stateOn(h, emptyList(), createdOn),
        )
    }

    @Test
    fun `a once per week habit is just a target of one`() {
        val h = habit(cadence = Cadence.TimesPerWeek(1))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)

        assertEquals(CellState.DONE, stateOn(h, entries(lastMonday.plusDays(4)), lastMonday))
        assertFalse("target of 1 shows a check, not a one-pip row", h.showsQuotaPips)
    }

    @Test
    fun `a negative weekly target is an allowance, not a floor`() {
        // "Eat out at most 2x per week".
        val h = habit(polarity = Polarity.NEGATIVE, cadence = Cadence.TimesPerWeek(2))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)

        val within = entries(lastMonday, lastMonday.plusDays(2))
        assertEquals(CellState.DONE, stateOn(h, within, lastMonday))

        val over = within + entries(lastMonday.plusDays(4))
        assertEquals(CellState.MISSED_ONCE, stateOn(h, over, lastMonday))
    }

    @Test
    fun `an allowance of one tolerates one, unlike a daily negative habit`() {
        // "Eat out at most once a week" must not mean "never eat out".
        val h = habit(polarity = Polarity.NEGATIVE, cadence = Cadence.TimesPerWeek(1))
        val lastMonday = HabitEvaluator.weekStart(today).minusWeeks(1)

        assertEquals(CellState.DONE, stateOn(h, entries(lastMonday), lastMonday))

        val twice = entries(lastMonday, lastMonday.plusDays(3))
        assertEquals(CellState.MISSED_ONCE, stateOn(h, twice, lastMonday))
    }

    @Test
    fun `weekly streaks are counted in weeks`() {
        val h = habit(cadence = Cadence.TimesPerWeek(1))
        val thisMonday = HabitEvaluator.weekStart(today)
        val done = (0..3).map { thisMonday.minusWeeks(it.toLong()) }
        assertEquals(4, timelineOf(h, entries(*done.toTypedArray())).stats.currentStreak)
    }

    @Test
    fun `weekly day level cells never show a day as a failure`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val states = timelineOf(h, entries(today.minusDays(2))).dayCells.map { it.state }

        assertTrue(
            "no individual day can fail a times-per-week habit",
            states.none { it == CellState.MISSED_ONCE || it == CellState.BROKEN },
        )
        assertTrue(states.contains(CellState.DONE))
    }

    // --- Slicing -----------------------------------------------------------------

    @Test
    fun `between overlaps rather than contains, so straddling weeks still count`() {
        val h = habit(cadence = Cadence.TimesPerWeek(1))
        val timeline = timelineOf(h, emptyList())

        // A Wednesday: the week containing it starts before it and must still be included.
        val wednesday = today.minusWeeks(2)
        val slice = timeline.between(wednesday, wednesday)

        assertEquals(1, slice.size)
        assertEquals(HabitEvaluator.weekStart(wednesday), slice.first().date)
    }

    // --- Rates -------------------------------------------------------------------

    @Test
    fun `completion rate ignores pending and unscheduled periods`() {
        val h = habit(cadence = Cadence.SpecificDays(setOf(DayOfWeek.MONDAY)))
        val mondays = range(start, today).filter { it.dayOfWeek == DayOfWeek.MONDAY }
        val done = mondays.drop(1) // missed the first one

        val stats = timelineOf(h, entries(*done.toTypedArray())).stats
        assertEquals(done.size.toFloat() / mondays.size, stats.completionRate, 0.001f)
    }

    @Test
    fun `a brand new habit has a rate of zero rather than dividing by zero`() {
        val h = habit(createdOn = today)
        assertEquals(0f, timelineOf(h, emptyList()).stats.completionRate, 0.0f)
    }
}
