package com.chainhabits.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class GoalHistoryTest {
    // A Wednesday. Its week starts on Monday 2026-08-10.
    private val today = LocalDate.of(2026, 8, 12)
    private val thisWeek = LocalDate.of(2026, 8, 10)
    private val created = today.minusWeeks(8)

    private val twice = Cadence.TimesPerWeek(2)
    private val thrice = Cadence.TimesPerWeek(3)
    private val monWedFri =
        Cadence.SpecificDays(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))

    private fun habit(
        cadence: Cadence,
        pastGoals: List<PastGoal> = emptyList(),
        createdOn: LocalDate = created,
    ) = Habit(
        id = 1,
        name = "test",
        cadence = cadence,
        createdOn = createdOn,
        pastGoals = pastGoals,
    )

    /** Every week from [from] through [to], each holding [perWeek] events on its Monday. */
    private fun weeklyEntries(
        from: LocalDate,
        to: LocalDate,
        perWeek: Int,
    ): List<Entry> =
        generateSequence(HabitEvaluator.weekStart(from)) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(to) }
            .map { Entry(1, it, perWeek) }
            .toList()

    // --- What an edit records -----------------------------------------------------

    @Test
    fun `raising a weekly target records the old one through last Sunday`() {
        val goals = GoalHistory.afterEdit(habit(twice), thrice, today)

        assertEquals(listOf(PastGoal(twice, thisWeek.minusDays(1))), goals)
    }

    @Test
    fun `changing a daily schedule records the old one through yesterday`() {
        val goals = GoalHistory.afterEdit(habit(Cadence.Daily), monWedFri, today)

        assertEquals(listOf(PastGoal(Cadence.Daily, today.minusDays(1))), goals)
    }

    @Test
    fun `an unchanged goal leaves the history alone`() {
        val past = listOf(PastGoal(Cadence.TimesPerWeek(1), created.plusWeeks(2)))

        assertEquals(past, GoalHistory.afterEdit(habit(twice, past), twice, today))
    }

    @Test
    fun `editing twice in one week keeps the goal the week started with`() {
        val afterFirst = GoalHistory.afterEdit(habit(twice), thrice, today)
        val afterSecond =
            GoalHistory.afterEdit(habit(thrice, afterFirst), Cadence.TimesPerWeek(4), today)

        assertEquals(listOf(PastGoal(twice, thisWeek.minusDays(1))), afterSecond)
    }

    @Test
    fun `changing back within the same week leaves no trace`() {
        val afterFirst = GoalHistory.afterEdit(habit(twice), thrice, today)

        assertEquals(
            emptyList<PastGoal>(),
            GoalHistory.afterEdit(habit(thrice, afterFirst), twice, today),
        )
    }

    @Test
    fun `successive changes in different weeks each keep their own goal`() {
        val lastWeek = today.minusWeeks(1)
        val first = GoalHistory.afterEdit(habit(Cadence.TimesPerWeek(1)), twice, lastWeek)
        val second = GoalHistory.afterEdit(habit(twice, first), thrice, today)

        assertEquals(
            listOf(
                PastGoal(Cadence.TimesPerWeek(1), thisWeek.minusWeeks(1).minusDays(1)),
                PastGoal(twice, thisWeek.minusDays(1)),
            ),
            second,
        )
    }

    @Test
    fun `a habit created this week has nothing settled to protect`() {
        val fresh = habit(twice, createdOn = today.minusDays(1))

        assertEquals(emptyList<PastGoal>(), GoalHistory.afterEdit(fresh, thrice, today))
    }

    @Test
    fun `switching between daily and weekly drops the history`() {
        val past = listOf(PastGoal(Cadence.TimesPerWeek(1), created.plusWeeks(2)))

        assertTrue(GoalHistory.afterEdit(habit(twice, past), Cadence.Daily, today).isEmpty())
        assertTrue(GoalHistory.afterEdit(habit(Cadence.Daily), twice, today).isEmpty())
    }

    // --- How the evaluator reads it -------------------------------------------------

    @Test
    fun `raising the target does not fail weeks that met the old one`() {
        val entries = weeklyEntries(created, thisWeek.minusDays(1), perWeek = 2)
        val raised = habit(thrice, GoalHistory.afterEdit(habit(twice), thrice, today))

        val settled = HabitEvaluator.timeline(raised, entries, today).cells.dropLast(1)

        assertTrue(
            "every settled week met its goal of the time",
            settled.all { it.state == CellState.DONE || it.state == CellState.NOT_SCHEDULED },
        )
        assertTrue(settled.any { it.state == CellState.DONE })
    }

    @Test
    fun `without the history the same edit would fail them`() {
        // The bug this feature fixes, pinned so the test above can't pass vacuously.
        val entries = weeklyEntries(created, thisWeek.minusDays(1), perWeek = 2)

        val cells = HabitEvaluator.timeline(habit(thrice), entries, today).cells.dropLast(1)

        assertTrue(cells.any { it.state == CellState.BROKEN })
    }

    @Test
    fun `the current week is judged against the new target`() {
        val raised = habit(thrice, GoalHistory.afterEdit(habit(twice), thrice, today))
        val entries = listOf(Entry(1, thisWeek, 2))

        val timeline = HabitEvaluator.timeline(raised, entries, today)

        assertEquals(CellState.PENDING, timeline.current?.state)
    }

    @Test
    fun `lowering an allowance does not fail weeks that kept the old one`() {
        val negative = { c: Cadence, past: List<PastGoal> ->
            habit(c, past).copy(polarity = Polarity.NEGATIVE)
        }
        val once = Cadence.TimesPerWeek(1)
        val lowered =
            negative(once, GoalHistory.afterEdit(negative(thrice, emptyList()), once, today))
        val entries = weeklyEntries(created, thisWeek.minusDays(1), perWeek = 3)

        val settled = HabitEvaluator.timeline(lowered, entries, today).cells.dropLast(1)

        assertTrue(
            settled.none { it.state == CellState.MISSED_ONCE || it.state == CellState.BROKEN },
        )
    }

    @Test
    fun `days before a schedule change keep the old schedule`() {
        // Was daily, now Mon/Wed/Fri. Last Tuesday was scheduled then and was missed.
        val lastTuesday = today.minusDays(8)
        val changed =
            habit(monWedFri, GoalHistory.afterEdit(habit(Cadence.Daily), monWedFri, today))
        val entries =
            generateSequence(created) { it.plusDays(1) }
                .takeWhile { it.isBefore(today) && it != lastTuesday }
                .map { Entry(1, it, 1) }
                .toList()

        val cells = HabitEvaluator.timeline(changed, entries, today).cells

        assertEquals(CellState.MISSED_ONCE, cells.first { it.date == lastTuesday }.state)
        assertTrue(
            "was scheduled under the old goal",
            HabitEvaluator.isScheduled(changed, lastTuesday),
        )
        assertFalse(
            "next Tuesday is off under the new one",
            HabitEvaluator.isScheduled(changed, lastTuesday.plusWeeks(2)),
        )
    }

    @Test
    fun `cadenceOn picks the goal in force on each date`() {
        val past =
            listOf(
                PastGoal(Cadence.TimesPerWeek(1), LocalDate.of(2026, 7, 5)),
                PastGoal(twice, LocalDate.of(2026, 7, 19)),
            )
        val h = habit(thrice, past)

        assertEquals(Cadence.TimesPerWeek(1), h.cadenceOn(LocalDate.of(2026, 7, 5)))
        assertEquals(twice, h.cadenceOn(LocalDate.of(2026, 7, 6)))
        assertEquals(twice, h.cadenceOn(LocalDate.of(2026, 7, 19)))
        assertEquals(thrice, h.cadenceOn(LocalDate.of(2026, 7, 20)))
    }
}
