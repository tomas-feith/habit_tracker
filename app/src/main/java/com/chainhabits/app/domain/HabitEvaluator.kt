package com.chainhabits.app.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Turns a habit plus its logged entries into mosaic cells and stats.
 *
 * Pure functions over [LocalDate] - no Android, no database, no clock. "Today" is always
 * passed in, which keeps the rollover rules testable.
 *
 * The two rules everything else follows from:
 *
 *  1. An untouched period settles according to polarity: a positive habit's becomes a
 *     miss, a negative habit's becomes a success.
 *  2. A period settles *early* when its outcome is already determined - completing a
 *     positive habit turns today solid immediately, and slipping on a negative one marks
 *     it immediately. A period is only [PeriodStatus.PENDING] while genuinely open.
 *
 * Always evaluate the habit's *whole* history via [timeline] and slice afterwards. The
 * "never miss twice" rule is order-dependent, so evaluating a window directly would let
 * the miss-counter restart at the window's left edge and paint a broken chain as merely
 * amber.
 */
object HabitEvaluator {
    private const val DAYS_PER_WEEK = 7

    /**
     * Lower bound for "every entry ever", for callers loading a habit's full history.
     *
     * Deliberately not `LocalDate.EPOCH`, which is API 33 and would crash on anything
     * older - this app supports API 26.
     */
    val BEGINNING_OF_TIME: LocalDate = LocalDate.ofEpochDay(0)

    /** Days of history behind the home screen's daily strip, including today. */
    private const val INLINE_DAYS = 28L

    /** Weeks of history behind the home screen's weekly strip, including this week. */
    private const val INLINE_WEEKS = 12L

    /** The Monday starting the week containing [date]. */
    fun weekStart(date: LocalDate): LocalDate {
        // DayOfWeek is Mon=1..Sun=7; shift so Monday maps to an offset of zero.
        val offsetFromMonday = (date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()
        return date.minusDays(offsetFromMonday)
    }

    /** Evaluates [habit]'s entire history up to [today]. */
    fun timeline(
        habit: Habit,
        entries: List<Entry>,
        today: LocalDate,
        pauses: List<Pause> = emptyList(),
    ): Timeline {
        val counts = entries.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.count } }
        val paused = Paused(pauses)

        val periods =
            when (val cadence = habit.cadence) {
                is Cadence.TimesPerWeek -> weeklyPeriods(habit, cadence, counts, today, paused)
                else -> dailyPeriods(habit, counts, today, paused)
            }
        val cells = applyStrictness(habit, periods)

        return Timeline(
            habit = habit,
            cells = cells,
            // Pauses need no special handling here: an unlogged day already renders as
            // unscheduled, and a workout you did manage on holiday still deserves its mark.
            dayCells = if (habit.isWeekly) dayLevelCells(habit, counts, today) else cells,
        )
    }

    /**
     * The paused stretches for one habit, as a question you can ask about a date.
     *
     * Wrapped rather than passed around as a raw list so the "is this paused" rule lives in
     * one place, and so an empty list costs nothing.
     */
    private class Paused(
        private val pauses: List<Pause>,
    ) {
        fun on(date: LocalDate): Boolean = pauses.any { it.covers(date) }

        fun during(
            from: LocalDate,
            to: LocalDate,
        ): Boolean = pauses.any { it.overlaps(from, to) }
    }

    /** Where the home screen's inline strip starts: 4 weeks of days, or 12 weeks. */
    fun inlineWindowStart(
        habit: Habit,
        today: LocalDate,
    ): LocalDate =
        if (habit.isWeekly) {
            weekStart(today).minusWeeks(INLINE_WEEKS - 1)
        } else {
            today.minusDays(INLINE_DAYS - 1)
        }

    private data class Period(
        val date: LocalDate,
        val status: PeriodStatus,
        val count: Int,
    )

    private fun dailyPeriods(
        habit: Habit,
        counts: Map<LocalDate, Int>,
        today: LocalDate,
        paused: Paused,
    ): List<Period> {
        val out = mutableListOf<Period>()
        var date = habit.createdOn
        while (!date.isAfter(today)) {
            val count = counts[date] ?: 0
            val status =
                when {
                    // A paused day carries no judgement, so the chain runs straight through
                    // it rather than being broken by a holiday - but work actually done is
                    // still credited, so pausing never confiscates a day you earned.
                    paused.on(date) -> pausedStatus(habit.polarity, count, floor = 1)

                    !isScheduled(habit, date) -> PeriodStatus.NOT_SCHEDULED

                    // A daily habit must be done once, and tolerates no slips at all.
                    date == today -> settleOpen(habit.polarity, count, floor = 1, allowance = 0)

                    else -> settleClosed(habit.polarity, count, floor = 1, allowance = 0)
                }
            out += Period(date, status, count)
            date = date.plusDays(1)
        }
        return out
    }

    private fun weeklyPeriods(
        habit: Habit,
        cadence: Cadence.TimesPerWeek,
        counts: Map<LocalDate, Int>,
        today: LocalDate,
        paused: Paused,
    ): List<Period> {
        val currentWeek = weekStart(today)
        val out = mutableListOf<Period>()
        var week = weekStart(habit.createdOn)

        while (!week.isAfter(currentWeek)) {
            val lastDay = week.plusDays((DAYS_PER_WEEK - 1).toLong())
            val total =
                (0 until DAYS_PER_WEEK).sumOf { counts[week.plusDays(it.toLong())] ?: 0 }
            val target = cadence.target

            val status =
                when {
                    week == currentWeek -> {
                        settleOpen(habit.polarity, total, target, target)
                    }

                    // A paused week is not judged, but a quota genuinely met during it
                    // still counts.
                    paused.during(week, lastDay) -> {
                        pausedStatus(habit.polarity, total, floor = target)
                    }

                    // A habit created on a Thursday only had part of that week available, so
                    // judging it against the full quota would open with an unearned miss.
                    // Credit it if the quota was somehow met, otherwise don't judge it.
                    isPartialWeek(habit, week) -> {
                        if (settleClosed(habit.polarity, total, target, target) ==
                            PeriodStatus.GOOD
                        ) {
                            PeriodStatus.GOOD
                        } else {
                            PeriodStatus.NOT_SCHEDULED
                        }
                    }

                    else -> {
                        settleClosed(habit.polarity, total, target, target)
                    }
                }
            out += Period(week, status, total)
            week = week.plusWeeks(1)
        }
        return out
    }

    /**
     * How a paused period settles.
     *
     * Credit is given only for something actually done, never merely for time passing.
     * That asymmetry is deliberate and the reason this is not [settleClosed]: for a
     * negative habit an untouched period is normally a success, so reusing the ordinary
     * rule would hand out a free clean streak for every day of a pause - which is the
     * opposite of what pausing means. A pause suspends the habit; it does not perform it.
     */
    private fun pausedStatus(
        polarity: Polarity,
        count: Int,
        floor: Int,
    ): PeriodStatus =
        if (polarity == Polarity.POSITIVE && count >= floor) {
            PeriodStatus.GOOD
        } else {
            PeriodStatus.NOT_SCHEDULED
        }

    /** True when the habit was created or archived partway through [week]. */
    private fun isPartialWeek(
        habit: Habit,
        week: LocalDate,
    ): Boolean {
        val lastDay = week.plusDays((DAYS_PER_WEEK - 1).toLong())
        if (habit.createdOn > week) return true
        habit.archivedOn?.let { if (it <= lastDay) return true }
        return false
    }

    /**
     * Day-level cells for the detail screen's heatmap, for weekly habits only.
     *
     * An unlogged day renders as unscheduled rather than as a miss, because for that
     * cadence no individual day can fail - only the week can fall short.
     */
    private fun dayLevelCells(
        habit: Habit,
        counts: Map<LocalDate, Int>,
        today: LocalDate,
    ): List<Cell> {
        val out = mutableListOf<Cell>()
        var date = habit.createdOn
        while (!date.isAfter(today)) {
            val count = counts[date] ?: 0
            out +=
                Cell(
                    date = date,
                    state = if (count > 0) CellState.DONE else CellState.NOT_SCHEDULED,
                    count = count,
                )
            date = date.plusDays(1)
        }
        return out
    }

    /**
     * A closed period.
     *
     * [floor] is what a positive habit must reach; [allowance] is what a negative habit
     * must stay within. They are passed separately on purpose: a daily habit has a floor
     * of 1 and an allowance of 0, whereas a weekly habit's target is both. Deriving one
     * from the other would make "at most once a week" mean "never".
     */
    private fun settleClosed(
        polarity: Polarity,
        count: Int,
        floor: Int,
        allowance: Int,
    ): PeriodStatus =
        when (polarity) {
            Polarity.POSITIVE -> if (count >= floor) PeriodStatus.GOOD else PeriodStatus.MISSED
            Polarity.NEGATIVE -> if (count <= allowance) PeriodStatus.GOOD else PeriodStatus.MISSED
        }

    /**
     * An open period, which settles early only when the outcome is already determined:
     * a positive habit you have already completed is solid now, and a slip past a
     * negative habit's allowance is a miss now. Anything else stays open.
     */
    private fun settleOpen(
        polarity: Polarity,
        count: Int,
        floor: Int,
        allowance: Int,
    ): PeriodStatus =
        when (polarity) {
            Polarity.POSITIVE -> {
                if (count >= floor) PeriodStatus.GOOD else PeriodStatus.PENDING
            }

            Polarity.NEGATIVE -> {
                if (count >
                    allowance
                ) {
                    PeriodStatus.MISSED
                } else {
                    PeriodStatus.PENDING
                }
            }
        }

    private fun isScheduled(
        habit: Habit,
        date: LocalDate,
    ): Boolean {
        if (date < habit.createdOn) return false
        habit.archivedOn?.let { if (date >= it) return false }
        return when (val c = habit.cadence) {
            is Cadence.Daily -> true
            is Cadence.SpecificDays -> date.dayOfWeek in c.days
            is Cadence.TimesPerWeek -> true
        }
    }

    /**
     * Turns settled statuses into drawable cells by applying strictness.
     *
     * Standard: the first miss is amber and recoverable, a second consecutive miss breaks
     * the chain. The amber cell is left amber rather than retroactively reddened - the
     * pair reads as "warned, then broke", which tells the story better than two reds.
     *
     * Strict: any miss breaks the chain immediately.
     */
    private fun applyStrictness(
        habit: Habit,
        periods: List<Period>,
    ): List<Cell> {
        var consecutiveMisses = 0
        return periods.map { period ->
            val state =
                when (period.status) {
                    PeriodStatus.NOT_SCHEDULED -> {
                        CellState.NOT_SCHEDULED
                    }

                    PeriodStatus.PENDING -> {
                        CellState.PENDING
                    }

                    PeriodStatus.GOOD -> {
                        consecutiveMisses = 0
                        CellState.DONE
                    }

                    PeriodStatus.MISSED -> {
                        consecutiveMisses++
                        if (habit.strictness == Strictness.STRICT || consecutiveMisses >= 2) {
                            CellState.BROKEN
                        } else {
                            CellState.MISSED_ONCE
                        }
                    }
                }
            Cell(period.date, state, period.count)
        }
    }
}
