package com.chainhabits.app.domain

import java.time.LocalDate

/**
 * Which past days a forgotten log can still be corrected on.
 *
 * Forgetting to tap is a real failure mode - the habit happened, the record didn't - and a
 * tracker that can't record it teaches you to distrust its own numbers. But an unlimited
 * editor turns the streak into something you can simply write, and the streak is the only
 * thing this app actually offers. A rolling window is the compromise: long enough to
 * survive a few days away, short enough that history settles and stays settled.
 *
 * Pure functions over [LocalDate], like [HabitEvaluator] - the window is a domain rule, not
 * a UI detail, so the screen and the view model can't disagree about what is editable.
 */
object Backfill {
    /** Days that stay open for correction, counting today as the first. */
    const val WINDOW_DAYS = 7

    /** The oldest day still correctable on [today]. */
    fun windowStart(today: LocalDate): LocalDate = today.minusDays(WINDOW_DAYS - 1L)

    /**
     * True when [date] can still be logged or cleared for [habit].
     *
     * Unscheduled days are excluded rather than merely discouraged: for a Mon/Wed/Fri
     * habit, a count written against a Tuesday changes nothing the evaluator will ever
     * read, so offering the button would promise an edit the mosaic then ignores. That
     * check also covers days before the habit existed and days after it was archived.
     */
    fun isEditable(
        habit: Habit,
        date: LocalDate,
        today: LocalDate,
    ): Boolean {
        if (date.isAfter(today)) return false
        if (date.isBefore(windowStart(today))) return false
        return HabitEvaluator.isScheduled(habit, date)
    }
}
