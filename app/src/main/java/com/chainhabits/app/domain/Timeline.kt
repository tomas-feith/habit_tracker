package com.chainhabits.app.domain

import java.time.LocalDate

/** Days in a week, for stepping over weekly periods. */
private const val DAYS_PER_WEEK = 7L

/**
 * A habit's fully evaluated history, produced by [HabitEvaluator.timeline].
 *
 * Holding the whole history in one object is what keeps the views consistent: the home
 * strip, the detail heatmap and the monthly chart are all slices of the same evaluation,
 * so none of them can disagree about whether a chain broke. Slice with [since] or
 * [between]; never re-evaluate a sub-range.
 */
class Timeline internal constructor(
    private val habit: Habit,
    /** Period-level cells over the habit's whole life, oldest first. */
    val cells: List<Cell>,
    /** Day-level cells. The same list as [cells] unless the habit is times-per-week. */
    val dayCells: List<Cell>,
) {
    /** The current period: today, or the current week for a times-per-week habit. */
    val current: Cell? get() = cells.lastOrNull()

    /** Events logged in the current period - drives "2 of 3 this week". */
    val currentCount: Int get() = current?.count ?: 0

    val stats: HabitStats by lazy(LazyThreadSafetyMode.NONE) { computeStats() }

    /** Period cells from [from] onward. */
    fun since(from: LocalDate): List<Cell> = cells.filter { !it.date.isBefore(from) }

    /** Day cells from [from] onward, for the year heatmap. */
    fun dayCellsSince(from: LocalDate): List<Cell> = dayCells.filter { !it.date.isBefore(from) }

    /**
     * Period cells overlapping [from]..[to] inclusive.
     *
     * Overlap rather than containment, so a week straddling a month boundary still counts
     * toward that month instead of vanishing from both.
     */
    fun between(
        from: LocalDate,
        to: LocalDate,
    ): List<Cell> =
        cells.filter { cell ->
            val end = if (habit.isWeekly) cell.date.plusDays(DAYS_PER_WEEK - 1) else cell.date
            !end.isBefore(from) && !cell.date.isAfter(to)
        }

    private fun computeStats(): HabitStats {
        val totals = tally()
        val live = walkBackFromNow()

        return HabitStats(
            currentStreak = live.streak,
            chainLength = live.chain,
            longestStreak = totals.longestRun,
            completionRate = cells.completionRate(),
            atRisk = isAtRisk(),
        )
    }

    private data class Totals(
        val longestRun: Int,
    )

    /** Single forward pass for the lifetime aggregates. */
    private fun tally(): Totals {
        var longest = 0
        var run = 0

        for (cell in cells) {
            when (cell.state) {
                CellState.DONE -> {
                    run++
                    if (run > longest) longest = run
                }

                CellState.MISSED_ONCE, CellState.BROKEN -> {
                    run = 0
                }

                // Pending and unscheduled periods neither extend nor break a run.
                CellState.PENDING, CellState.NOT_SCHEDULED -> {}
            }
        }
        return Totals(longestRun = longest)
    }

    private data class Live(
        val streak: Int,
        val chain: Int,
    )

    /**
     * Single backward pass for the two live numbers.
     *
     * They differ deliberately: the streak stops at the first miss and is the honest
     * count, while the chain runs on through isolated misses and stops only where it
     * actually broke. That is what lets one sick day cost you a streak without
     * presenting as total failure.
     */
    private fun walkBackFromNow(): Live {
        var streak = 0
        var streakDone = false
        var chain = 0
        var chainDone = false

        // Unscheduled periods are dropped up front rather than skipped inside the loop:
        // they carry no judgement, so they must neither extend nor interrupt either count.
        val judged = cells.asReversed().filter { it.state != CellState.NOT_SCHEDULED }

        for (cell in judged) {
            if (streakDone && chainDone) break

            when {
                countsAsGood(cell) -> {
                    if (!streakDone) streak++
                    if (!chainDone) chain++
                }

                // An open period on a positive habit simply isn't done yet.
                cell.state == CellState.PENDING -> {}

                else -> {
                    streakDone = true
                    when {
                        cell.state == CellState.BROKEN -> chainDone = true
                        !chainDone -> chain++
                    }
                }
            }
        }
        return Live(streak = streak, chain = chain)
    }

    /**
     * An open period on a negative habit is provisionally clean, so it counts toward
     * "days since last slip". On a positive habit it does not count until you do it.
     */
    private fun countsAsGood(cell: Cell): Boolean =
        cell.state == CellState.DONE ||
            (cell.state == CellState.PENDING && habit.polarity == Polarity.NEGATIVE)

    /**
     * True when one more miss would break the chain.
     *
     * Never true for a strict habit: there the chain has already broken rather than
     * being at risk, so warning about it would be misleading.
     */
    private fun isAtRisk(): Boolean {
        if (habit.strictness != Strictness.STANDARD) return false
        val lastSettled =
            cells.lastOrNull {
                it.state != CellState.NOT_SCHEDULED && it.state != CellState.PENDING
            }
        return lastSettled?.state == CellState.MISSED_ONCE
    }
}

/** Share of judged periods in this list that were good. Pending/unscheduled are ignored. */
fun List<Cell>.completionRate(): Float {
    val judged =
        count {
            it.state == CellState.DONE ||
                it.state == CellState.MISSED_ONCE ||
                it.state == CellState.BROKEN
        }
    if (judged == 0) return 0f
    return count { it.state == CellState.DONE }.toFloat() / judged
}
