package com.chainhabits.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Whether the habit is something you want to do or something you want to avoid.
 *
 * This determines the default state of an untouched period at rollover:
 * a [POSITIVE] habit you never logged is a miss, a [NEGATIVE] habit you never logged is
 * a success. It does *not* change how the mosaic reads - a solid cell always means "a
 * good day" for both.
 */
enum class Polarity {
    /** You log completions. "Cook healthy food." */
    POSITIVE,

    /** You log slips. "Don't buy McDonald's." */
    NEGATIVE,
}

/**
 * How harshly a miss is treated.
 *
 * [STANDARD] follows Clear's "never miss twice": an isolated miss is noise, two in a row
 * is the start of a new habit. [STRICT] is for habits where the single instance is itself
 * the harm rather than a data point in a trend.
 */
enum class Strictness {
    STANDARD,
    STRICT,
}

sealed interface Cadence {
    /** Every day counts. */
    data object Daily : Cadence

    /** Only the named days count; the rest render as "not scheduled". */
    data class SpecificDays(val days: Set<DayOfWeek>) : Cadence

    /**
     * The week is the unit of success, not the day. No individual day can be a failure.
     *
     * For a [Polarity.POSITIVE] habit the target is a floor ("workout at least 3x/week");
     * for a [Polarity.NEGATIVE] habit it is an allowance ("eat out at most 2x/week").
     */
    data class TimesPerWeek(val target: Int) : Cadence
}

data class Habit(
    val id: Long = 0,
    val name: String,
    val polarity: Polarity = Polarity.POSITIVE,
    val strictness: Strictness = Strictness.STANDARD,
    val cadence: Cadence = Cadence.Daily,
    val reminderTime: LocalTime? = null,
    val createdOn: LocalDate,
    val archivedOn: LocalDate? = null,
    val sortOrder: Int = 0,
) {
    val isWeekly: Boolean get() = cadence is Cadence.TimesPerWeek

    /** Weekly habits with a target of 1 show a plain check, not a one-pip progress row. */
    val showsQuotaPips: Boolean
        get() = (cadence as? Cadence.TimesPerWeek)?.let { it.target > 1 } ?: false
}

/**
 * One logged event on a date.
 *
 * For a [Polarity.POSITIVE] habit an event is a completion; for a [Polarity.NEGATIVE]
 * habit it is a slip. [count] lets a times-per-week habit record several on one day.
 */
data class Entry(
    val habitId: Long,
    val date: LocalDate,
    val count: Int,
)

/** Whether a single settled period met its bar. */
enum class PeriodStatus {
    GOOD,
    MISSED,

    /** The habit wasn't scheduled for this period. Carries no judgement either way. */
    NOT_SCHEDULED,

    /** Today, or the current week: not settled yet, so not yet judged. */
    PENDING,
}

/** How a mosaic cell should be drawn. */
enum class CellState {
    /** A good day (or week). Solid. */
    DONE,

    /** One miss, chain still intact. Quiet amber - noise, not failure. */
    MISSED_ONCE,

    /** Chain broken: a second consecutive miss, or any miss on a strict habit. */
    BROKEN,

    /** Not scheduled. Faint dash. */
    NOT_SCHEDULED,

    /** The current, unsettled period. Drawn with an outline. */
    PENDING,
}

/** One cell of the mosaic: a day for daily habits, a week for times-per-week habits. */
data class Cell(
    /** The date, or for weekly habits the Monday that starts the week. */
    val date: LocalDate,
    val state: CellState,
    /** Events logged in this period. Drives the "2 of 3 this week" readout. */
    val count: Int = 0,
)

data class HabitStats(
    /**
     * Consecutive good periods, counting back from now. Resets on any miss.
     * This is the honest number, and the headline for [Strictness.STRICT] habits where
     * "days since" is the whole point.
     */
    val currentStreak: Int,

    /**
     * Periods back to the last *broken* chain, tolerating isolated single misses.
     * The headline for [Strictness.STANDARD] habits, so one sick day doesn't wipe the
     * board and take your motivation with it.
     */
    val chainLength: Int,

    val longestStreak: Int,

    /** Good periods as a fraction of scheduled, settled periods. */
    val completionRate: Float,

    /**
     * True when the last settled period was a miss and one more would break the chain.
     * Drives the "never miss twice" banner. Always false for strict habits, where a miss
     * has already broken the chain rather than putting it at risk.
     */
    val atRisk: Boolean,
)
