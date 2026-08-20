package com.chainhabits.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Whether a habit's reminder is still worth firing, and when the next one is due.
 *
 * Pure functions over [LocalDate] / [LocalDateTime] - no Android, no database, no clock -
 * for the same reason [HabitEvaluator] is: this is the rule that decides whether the user
 * gets interrupted, and it should be testable without a device.
 *
 * The alarm is armed at the reminder time and the decision is made when it *fires*, not
 * when it is scheduled. That ordering is the point: what the habit looks like at 07:00 is
 * not what it looked like when the alarm was set the previous evening.
 */
object ReminderDecision {
    /**
     * True when [habit] still deserves a nudge on [today].
     *
     * [entries] must cover at least the week containing [today]; anything outside the
     * period being judged is ignored, so passing more is harmless.
     */
    fun shouldNotify(
        habit: Habit,
        entries: List<Entry>,
        pauses: List<Pause>,
        today: LocalDate,
    ): Boolean {
        if (!isEligible(habit, pauses, today)) return false

        return when (val cadence = habit.cadence) {
            // The week is the unit, so the quota is what closes it - a fourth workout in a
            // 3x week is welcome but not owed, and nagging for it turns a met goal into an
            // unmet one.
            is Cadence.TimesPerWeek -> weekTotal(entries, today) < cadence.target

            else -> countOn(entries, today) == 0
        }
    }

    /**
     * Whether a nudge is possible at all today, before looking at what has been logged.
     *
     * Separate from the cadence check because these are the reasons no amount of doing or
     * not doing the habit could change the answer.
     */
    private fun isEligible(
        habit: Habit,
        pauses: List<Pause>,
        today: LocalDate,
    ): Boolean =
        habit.reminderTime != null &&
            // A negative habit is satisfied by doing nothing, so no notification can ever
            // be actionable: "don't buy McDonald's" has no button to press. Reminding daily
            // just trains the user to swipe this app's notifications away, which costs us
            // the ones that do mean something.
            habit.polarity != Polarity.NEGATIVE &&
            // Covers archived and not-yet-created as well as the day-of-week cadence.
            HabitEvaluator.isScheduled(habit, today) &&
            // A pause is the user having said "not now". Honour it.
            pauses.none { it.covers(today) }

    /**
     * The next wall-clock moment at [time] strictly after [now].
     *
     * Derived from the local date each time rather than by adding 24h to the last firing:
     * a fixed 24-hour period is not a day. Across a DST boundary it drifts by an hour and
     * stays drifted, and after a flight it keeps firing on the old zone's schedule.
     */
    fun nextTriggerAfter(
        now: LocalDateTime,
        time: LocalTime,
    ): LocalDateTime {
        val todayAt = LocalDateTime.of(now.toLocalDate(), time)
        return if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
    }

    private fun countOn(
        entries: List<Entry>,
        date: LocalDate,
    ): Int = entries.filter { it.date == date }.sumOf { it.count }

    private fun weekTotal(
        entries: List<Entry>,
        today: LocalDate,
    ): Int {
        val start = HabitEvaluator.weekStart(today)
        val end = start.plusDays(6)
        return entries
            .filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            .sumOf { it.count }
    }
}
