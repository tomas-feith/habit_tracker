package com.chainhabits.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderDecisionTest {
    // A Wednesday, so week boundaries are unambiguous in the weekly tests.
    private val today = LocalDate.of(2026, 8, 12)
    private val start = today.minusDays(30)
    private val at7 = LocalTime.of(7, 0)

    private fun habit(
        polarity: Polarity = Polarity.POSITIVE,
        cadence: Cadence = Cadence.Daily,
        reminderTime: LocalTime? = at7,
        createdOn: LocalDate = start,
        archivedOn: LocalDate? = null,
    ) = Habit(
        id = 1,
        name = "test",
        polarity = polarity,
        cadence = cadence,
        reminderTime = reminderTime,
        createdOn = createdOn,
        archivedOn = archivedOn,
    )

    private fun entries(vararg days: Pair<LocalDate, Int>) =
        days.map { (date, count) -> Entry(1, date, count) }

    private fun shouldNotify(
        h: Habit,
        entries: List<Entry> = emptyList(),
        pauses: List<Pause> = emptyList(),
    ) = ReminderDecision.shouldNotify(h, entries, pauses, today)

    // --- the nudge is owed ---

    @Test
    fun `notifies an undone daily habit`() {
        assertTrue(shouldNotify(habit()))
    }

    @Test
    fun `notifies on a scheduled day of a specific-days habit`() {
        val h = habit(cadence = Cadence.SpecificDays(setOf(DayOfWeek.WEDNESDAY)))
        assertTrue(shouldNotify(h))
    }

    // --- already done ---

    @Test
    fun `stays quiet once the daily habit is logged`() {
        assertFalse(shouldNotify(habit(), entries(today to 1)))
    }

    @Test
    fun `a completion on another day does not silence today`() {
        assertTrue(shouldNotify(habit(), entries(today.minusDays(1) to 1)))
    }

    @Test
    fun `stays quiet once a weekly quota is met`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val week = HabitEvaluator.weekStart(today)
        val done = entries(week to 1, week.plusDays(1) to 1, week.plusDays(2) to 1)
        assertFalse(shouldNotify(h, done))
    }

    @Test
    fun `still nudges a weekly habit short of its quota`() {
        val h = habit(cadence = Cadence.TimesPerWeek(3))
        val week = HabitEvaluator.weekStart(today)
        assertTrue(shouldNotify(h, entries(week to 1, week.plusDays(1) to 1)))
    }

    @Test
    fun `last week's quota does not count toward this week`() {
        val h = habit(cadence = Cadence.TimesPerWeek(2))
        val lastWeek = HabitEvaluator.weekStart(today).minusWeeks(1)
        assertTrue(shouldNotify(h, entries(lastWeek to 2)))
    }

    @Test
    fun `a multi-count day can meet the quota on its own`() {
        val h = habit(cadence = Cadence.TimesPerWeek(2))
        assertFalse(shouldNotify(h, entries(today to 2)))
    }

    // --- never owed ---

    @Test
    fun `never notifies a negative habit`() {
        // Even with a reminder time still stored from before the habit was flipped.
        assertFalse(shouldNotify(habit(polarity = Polarity.NEGATIVE)))
    }

    @Test
    fun `stays quiet with no reminder time set`() {
        assertFalse(shouldNotify(habit(reminderTime = null)))
    }

    @Test
    fun `stays quiet on an unscheduled day`() {
        val h = habit(cadence = Cadence.SpecificDays(setOf(DayOfWeek.MONDAY)))
        assertFalse(shouldNotify(h))
    }

    @Test
    fun `stays quiet while paused`() {
        val open = Pause(habitId = 1, start = today.minusDays(2), end = null)
        assertFalse(shouldNotify(habit(), pauses = listOf(open)))
    }

    @Test
    fun `resumes nudging after a pause has ended`() {
        val past = Pause(habitId = 1, start = today.minusDays(5), end = today.minusDays(1))
        assertTrue(shouldNotify(habit(), pauses = listOf(past)))
    }

    @Test
    fun `stays quiet once archived`() {
        assertFalse(shouldNotify(habit(archivedOn = today.minusDays(1))))
    }

    // --- next trigger ---

    @Test
    fun `today's time is used when it is still ahead`() {
        val now = LocalDateTime.of(today, LocalTime.of(6, 30))
        assertEquals(LocalDateTime.of(today, at7), ReminderDecision.nextTriggerAfter(now, at7))
    }

    @Test
    fun `a time already past rolls to tomorrow`() {
        val now = LocalDateTime.of(today, LocalTime.of(7, 30))
        val next = ReminderDecision.nextTriggerAfter(now, at7)
        assertEquals(LocalDateTime.of(today.plusDays(1), at7), next)
    }

    @Test
    fun `the exact reminder minute rolls to tomorrow rather than firing twice`() {
        val now = LocalDateTime.of(today, at7)
        val next = ReminderDecision.nextTriggerAfter(now, at7)
        assertEquals(LocalDateTime.of(today.plusDays(1), at7), next)
    }

    /**
     * The regression the repeating alarm could not survive: adding a fixed 24 hours across
     * a DST boundary lands an hour out and stays out. Working in local time means the
     * reminder keeps its 07:00, and the real elapsed interval absorbs the lost hour.
     */
    @Test
    fun `keeps its wall-clock time across a DST transition`() {
        val zone = ZoneId.of("Europe/Lisbon")
        // Clocks go forward at 01:00 on 2026-03-29.
        val evening = LocalDateTime.of(2026, 3, 28, 20, 0)

        val next = ReminderDecision.nextTriggerAfter(evening, at7)
        assertEquals(LocalDateTime.of(2026, 3, 29, 7, 0), next)

        val elapsed =
            next.atZone(zone).toEpochSecond() - evening.atZone(zone).toEpochSecond()
        assertEquals(10 * 3600L, elapsed)
    }
}
