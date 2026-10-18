package com.chainhabits.app.domain

import java.time.LocalDate

/**
 * What changing a habit's goal does to its history.
 *
 * A new goal applies from the period in progress onward: this week for a times-per-week
 * habit, today for a daily one. Everything already settled keeps being judged against the
 * goal it actually had, so raising "2x a week" to "3x a week" asks more of you from now on
 * without reaching back and failing the weeks you did twice.
 *
 * The exception is switching between a daily and a weekly goal. Those are judged in
 * different units - a day against a week - so one mosaic cannot hold both, and the whole
 * history is re-judged under the new goal, exactly as before goals had a history.
 *
 * Pure functions over [LocalDate], like [HabitEvaluator].
 */
object GoalHistory {
    /**
     * The past goals [stored] should carry once its goal becomes [next] on [today].
     *
     * Editing twice inside one period records only the goal the period started with: that
     * is the one the settled periods before it were judged against, and the intermediate
     * one was never in force for a whole period. Changing the goal back within the same
     * period therefore leaves no trace.
     */
    fun afterEdit(
        stored: Habit,
        next: Cadence,
        today: LocalDate,
    ): List<PastGoal> {
        if (next == stored.cadence) return stored.pastGoals
        if ((next is Cadence.TimesPerWeek) != stored.isWeekly) return emptyList()

        val lastDay = currentPeriodStart(next, today).minusDays(1)
        val before = stored.pastGoals.filter { it.lastDay < lastDay }
        val endingNow = stored.pastGoals.firstOrNull { it.lastDay == lastDay }

        val recorded =
            when {
                endingNow != null -> before + endingNow

                // Created during the current period: there is nothing settled to protect.
                lastDay < stored.createdOn -> before

                else -> before + PastGoal(stored.cadence, lastDay)
            }

        // A trailing goal identical to the new one is just the new one starting earlier.
        return recorded.dropLastWhile { it.cadence == next }
    }

    private fun currentPeriodStart(
        cadence: Cadence,
        today: LocalDate,
    ): LocalDate = if (cadence is Cadence.TimesPerWeek) HabitEvaluator.weekStart(today) else today
}
