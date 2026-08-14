package com.chainhabits.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
 */
object HabitEvaluator {

    private data class Period(
        val date: LocalDate,
        val status: PeriodStatus,
        val count: Int,
    )

    /** The Monday starting the week containing [date]. */
    fun weekStart(date: LocalDate): LocalDate =
        date.minusDays(((date.dayOfWeek.value + 6) % 7).toLong())

    /**
     * Mosaic cells for [habit] over [from]..[today], oldest first.
     *
     * For a times-per-week habit one cell is one week and [from] is snapped back to that
     * week's Monday; otherwise one cell is one day.
     */
    fun cells(
        habit: Habit,
        entries: List<Entry>,
        from: LocalDate,
        today: LocalDate,
    ): List<Cell> {
        val periods = periods(habit, entries, from, today)
        val states = states(habit, periods.map { it.status })
        return periods.mapIndexed { i, p -> Cell(p.date, states[i], p.count) }
    }

    fun stats(habit: Habit, entries: List<Entry>, today: LocalDate): HabitStats {
        val periods = periods(habit, entries, habit.createdOn, today)
        val states = states(habit, periods.map { it.status })

        var longest = 0
        var run = 0
        var good = 0
        var missed = 0
        for (p in periods) {
            when (p.status) {
                PeriodStatus.GOOD -> {
                    good++; run++; if (run > longest) longest = run
                }
                PeriodStatus.MISSED -> {
                    missed++; run = 0
                }
                // Pending and unscheduled periods neither extend nor break a run.
                PeriodStatus.PENDING, PeriodStatus.NOT_SCHEDULED -> Unit
            }
        }

        // Walk backwards for the live numbers, ignoring unscheduled periods entirely.
        // The streak stops at the first miss; the chain runs on through isolated misses
        // and stops only where it actually broke.
        var currentStreak = 0
        var streakDone = false
        var chainLength = 0
        var chainDone = false
        for (i in periods.indices.reversed()) {
            val status = periods[i].status
            if (status == PeriodStatus.NOT_SCHEDULED) continue

            // An open period on a negative habit is provisionally clean, so it counts
            // toward "days since last slip". On a positive habit it just isn't done yet.
            val isGood = status == PeriodStatus.GOOD ||
                (status == PeriodStatus.PENDING && habit.polarity == Polarity.NEGATIVE)

            when {
                isGood -> {
                    if (!streakDone) currentStreak++
                    if (!chainDone) chainLength++
                }

                status == PeriodStatus.PENDING -> Unit

                else -> {
                    streakDone = true
                    if (states[i] == CellState.BROKEN) chainDone = true
                    else if (!chainDone) chainLength++
                }
            }
            if (streakDone && chainDone) break
        }

        val atRisk = habit.strictness == Strictness.STANDARD &&
            states.lastOrNull { it != CellState.NOT_SCHEDULED && it != CellState.PENDING } ==
            CellState.MISSED_ONCE

        val scheduled = good + missed
        return HabitStats(
            currentStreak = currentStreak,
            chainLength = chainLength,
            longestStreak = longest,
            completionRate = if (scheduled == 0) 0f else good.toFloat() / scheduled,
            atRisk = atRisk,
        )
    }

    private fun periods(
        habit: Habit,
        entries: List<Entry>,
        from: LocalDate,
        today: LocalDate,
    ): List<Period> {
        val counts = entries.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.count } }
        return when (val cadence = habit.cadence) {
            is Cadence.TimesPerWeek -> weeklyPeriods(habit, cadence, counts, from, today)
            else -> dailyPeriods(habit, counts, from, today)
        }
    }

    private fun dailyPeriods(
        habit: Habit,
        counts: Map<LocalDate, Int>,
        from: LocalDate,
        today: LocalDate,
    ): List<Period> {
        val out = mutableListOf<Period>()
        var date = from
        while (!date.isAfter(today)) {
            val count = counts[date] ?: 0
            val scheduled = isScheduled(habit, date)
            val status = when {
                !scheduled -> PeriodStatus.NOT_SCHEDULED
                date == today -> settleOpen(habit.polarity, count, target = 1)
                else -> settleClosed(habit.polarity, count, target = 1)
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
        from: LocalDate,
        today: LocalDate,
    ): List<Period> {
        val currentWeek = weekStart(today)
        val out = mutableListOf<Period>()
        var week = weekStart(maxOf(from, habit.createdOn))
        while (!week.isAfter(currentWeek)) {
            val total = (0..6).sumOf { counts[week.plusDays(it.toLong())] ?: 0 }
            val status = when {
                // Only judge weeks the habit actually existed for.
                week.plusDays(6) < habit.createdOn -> PeriodStatus.NOT_SCHEDULED
                week == currentWeek -> settleOpen(habit.polarity, total, cadence.target)
                else -> settleClosed(habit.polarity, total, cadence.target)
            }
            out += Period(week, status, total)
            week = week.plusWeeks(1)
        }
        return out
    }

    /**
     * A closed period. For a positive habit the target is a floor (do it at least N
     * times); for a negative habit it is an allowance (slip at most N times, and the
     * default allowance of a plain negative habit is zero).
     */
    private fun settleClosed(polarity: Polarity, count: Int, target: Int): PeriodStatus =
        when (polarity) {
            Polarity.POSITIVE -> if (count >= target) PeriodStatus.GOOD else PeriodStatus.MISSED
            Polarity.NEGATIVE -> if (count <= allowance(target)) PeriodStatus.GOOD else PeriodStatus.MISSED
        }

    /**
     * An open period, which settles early only when the outcome is already determined:
     * a positive habit you have already completed is solid now, and a slip on a negative
     * habit is a slip now. Anything else stays open.
     */
    private fun settleOpen(polarity: Polarity, count: Int, target: Int): PeriodStatus =
        when (polarity) {
            Polarity.POSITIVE -> if (count >= target) PeriodStatus.GOOD else PeriodStatus.PENDING
            Polarity.NEGATIVE -> if (count > allowance(target)) PeriodStatus.MISSED else PeriodStatus.PENDING
        }

    /** A daily negative habit tolerates nothing; a weekly one tolerates its target. */
    private fun allowance(target: Int) = if (target <= 1) 0 else target

    private fun isScheduled(habit: Habit, date: LocalDate): Boolean {
        if (date < habit.createdOn) return false
        habit.archivedOn?.let { if (date >= it) return false }
        return when (val c = habit.cadence) {
            is Cadence.Daily -> true
            is Cadence.SpecificDays -> date.dayOfWeek in c.days
            is Cadence.TimesPerWeek -> true
        }
    }

    /**
     * Applies strictness to a run of period statuses.
     *
     * Standard: the first miss is amber and recoverable, a second consecutive miss breaks
     * the chain. The amber cell is left amber rather than retroactively reddened - the
     * pair reads as "warned, then broke", which tells the story better than two reds.
     *
     * Strict: any miss breaks the chain immediately.
     */
    private fun states(habit: Habit, statuses: List<PeriodStatus>): List<CellState> {
        var consecutiveMisses = 0
        return statuses.map { status ->
            when (status) {
                PeriodStatus.NOT_SCHEDULED -> CellState.NOT_SCHEDULED
                PeriodStatus.PENDING -> CellState.PENDING
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
        }
    }

    /**
     * Day-level cells for the detail screen's heatmap.
     *
     * Identical to [cells] except for times-per-week habits, whose inline strip is drawn
     * in weeks. Here they are broken back down to days so you can see *which* days you
     * worked out - but an unlogged day renders as unscheduled rather than as a miss,
     * because for that cadence no individual day can fail.
     */
    fun dayLevelCells(
        habit: Habit,
        entries: List<Entry>,
        from: LocalDate,
        today: LocalDate,
    ): List<Cell> {
        if (!habit.isWeekly) return cells(habit, entries, from, today)

        val counts = entries.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.count } }
        val out = mutableListOf<Cell>()
        var date = from
        while (!date.isAfter(today)) {
            val count = counts[date] ?: 0
            val state = if (count > 0) CellState.DONE else CellState.NOT_SCHEDULED
            out += Cell(date, state, count)
            date = date.plusDays(1)
        }
        return out
    }

    /** Number of cells to show inline on the home screen. */
    fun inlineWindowStart(habit: Habit, today: LocalDate): LocalDate =
        if (habit.isWeekly) weekStart(today).minusWeeks(11) else today.minusDays(27)

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
