package com.chainhabits.app.domain

/**
 * What a reminder actually says.
 *
 * Split out of [com.chainhabits.app.notify.Reminders] and kept pure - no Android, no
 * database, no clock - for the same reason [ReminderDecision] is: this is the sentence the
 * user reads at 07:00, and it should be checkable without a device.
 *
 * The point of the wording is loss, not instruction. "Still time today" tells you nothing
 * you did not know; naming what a miss would cost is the thing that actually moves anyone,
 * and the number is already sitting in [HabitStats].
 *
 * The one rule the wording must not break: **never threaten a loss that would not happen.**
 * Under [Strictness.STANDARD] an isolated miss is amber and recoverable, so telling a
 * 47-day chain it dies tonight would be a lie, and a tracker that cries wolf gets its
 * notifications turned off. The sharp wording is therefore reserved for the two cases where
 * the threat is real - a strict habit, and a standard habit already sitting on one miss -
 * and everything else leans on what has been built instead of what could be lost.
 */
object ReminderCopy {
    /**
     * The notification's two lines.
     *
     * [title] stays the habit name: it is what tells one reminder from another in a shade
     * holding five of them, so the number goes in [body] where it has room to say what it
     * means.
     */
    data class Text(
        val title: String,
        val body: String,
    )

    /**
     * The reminder for [habit], given its evaluated [stats] and what has been logged in the
     * current period.
     *
     * Only positive habits ever reach here - see [ReminderDecision.shouldNotify] - so there
     * is no wording for a negative habit's nudge.
     */
    fun forHabit(
        habit: Habit,
        stats: HabitStats,
        currentCount: Int,
    ): Text =
        Text(
            title = habit.name,
            body =
                when (val cadence = habit.cadence) {
                    is Cadence.TimesPerWeek -> weeklyBody(habit, stats, currentCount, cadence)
                    else -> dailyBody(habit, stats)
                },
        )

    private fun dailyBody(
        habit: Habit,
        stats: HabitStats,
    ): String {
        val n = stats.headlineFor(habit)
        return when {
            // The only moment the chain is genuinely one miss from gone. Says so plainly,
            // and names the number that would go with it.
            stats.atRisk -> {
                "You missed yesterday. Miss again and ${days(n)} goes with it."
            }

            // A strict habit has no second chance by definition, so the threat is honest.
            habit.strictness == Strictness.STRICT && n > 0 -> {
                "${days(n)} clean. One miss starts the count again."
            }

            // Nothing to lose yet, so nothing is claimed. Framed as a beginning rather than
            // as a zero, which is the same fact and a much better invitation.
            n == 0 -> {
                "No chain yet. Today is day one."
            }

            stats.isRecord -> {
                "Day $n, your longest run yet. Still time today."
            }

            else -> {
                "Day $n. Still time to keep the chain."
            }
        }
    }

    /**
     * The weekly wording, which leads with the quota rather than the streak.
     *
     * What is actually at stake before Sunday is the week's target, and "1 of 3 this week"
     * is both the more urgent fact and the more actionable one. The streak clause follows
     * only when there is a streak worth naming.
     */
    private fun weeklyBody(
        habit: Habit,
        stats: HabitStats,
        currentCount: Int,
        cadence: Cadence.TimesPerWeek,
    ): String {
        val n = stats.headlineFor(habit)
        val remaining = (cadence.target - currentCount).coerceAtLeast(1)
        val quota =
            if (cadence.target > 1) {
                "$currentCount of ${cadence.target} this week, $remaining to go."
            } else {
                "Not done this week yet."
            }

        val stake =
            when {
                stats.atRisk -> {
                    "Fall short again and ${weeks(n)} goes with it."
                }

                habit.strictness == Strictness.STRICT && n > 0 -> {
                    "${weeks(n)} clean, and one short week starts the count again."
                }

                n == 0 -> {
                    "Week one starts here."
                }

                stats.isRecord -> {
                    "${weeks(n)} so far, your longest run yet."
                }

                else -> {
                    "${weeks(n)} so far."
                }
            }
        return "$quota $stake"
    }

    private fun days(n: Int): String = plural(n, "day")

    private fun weeks(n: Int): String = plural(n, "week")

    private fun plural(
        n: Int,
        unit: String,
    ): String = if (n == 1) "1 $unit" else "$n ${unit}s"
}
