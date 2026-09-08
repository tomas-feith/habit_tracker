package com.chainhabits.app.widget

import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.CellState
import com.chainhabits.app.domain.Entry
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.HabitEvaluator
import com.chainhabits.app.domain.Pause
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.headlineFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * One habit as the widget needs it: already evaluated, with nothing left to compute at
 * draw time.
 *
 * The widget renders in the launcher's process through `RemoteViews`, so the less it has
 * to do while composing the better. Everything here comes from the same
 * [HabitEvaluator.timeline] the app screens use, which is what stops the widget and the
 * home screen from ever disagreeing about whether a chain is intact.
 */
data class WidgetRow(
    val id: Long,
    val name: String,
    val state: CellState,
    /** Events logged in the current period. */
    val count: Int,
    /** The weekly quota, or null for a daily habit. */
    val target: Int?,
    /** The number the row leads with - chain for standard habits, streak for strict. */
    val headline: Int,
    val isWeekly: Boolean,
    val polarity: Polarity,
    /** True while a pause is running, so the row reads as suspended rather than undone. */
    val isPaused: Boolean,
    /**
     * Why it is paused, when the user said.
     *
     * Carried onto the launcher because "paused" alone invites the question the answer to
     * which is already stored - and a widget that says "paused - injured leg" is the one
     * place the reason is read without opening anything.
     */
    val pauseReason: String? = null,
    /**
     * True when one more miss would break the chain.
     *
     * The widget's whole advantage over a notification is that it is already on screen, so
     * this is the one thing it can say that nothing else can say for free: the warning sits
     * in front of the user all day instead of arriving once and being swiped away.
     */
    val atRisk: Boolean,
) {
    /** Whether the current period's bar is already met. */
    val isSatisfied: Boolean get() = target?.let { count >= it } ?: (count > 0)

    /** Whether the tile is drawn solid - a good period, by the mosaic's one rule. */
    val isFilled: Boolean get() = isSatisfied || state == CellState.DONE

    /**
     * Whether the tile shows a running count rather than an empty box.
     *
     * Only for a weekly habit part-way through its quota, where "2" says something an
     * empty square does not.
     */
    val showsPartialCount: Boolean
        get() = !isFilled && count > 0 && (target ?: 0) > 1

    /**
     * Whether tapping the row logs an event.
     *
     * Negative habits are deliberately read-only here. They need no daily action - a clean
     * day is the default and you only ever log slips - so the only thing a tap could do is
     * record a slip. A stray pocket tap that silently breaks a strict chain is a much worse
     * outcome than making you open the app to admit one, and the widget offers no
     * confirmation step to soften it.
     */
    val isTappable: Boolean get() = polarity == Polarity.POSITIVE && !isPaused

    /** "3 of 5" for weekly habits, "2 weeks"/"9 days" otherwise. */
    val subtitle: String
        get() =
            when {
                isPaused -> {
                    pauseReason?.let { "paused - $it" } ?: "paused"
                }

                // At risk outranks the plain count, including for a weekly habit, where the
                // quota is kept alongside it because it is the thing you would act on.
                isAtRiskNow && target != null && target > 1 -> {
                    "$count of $target, chain at risk"
                }

                isAtRiskNow -> {
                    "$headline ${plural(headline, unit)} at risk"
                }

                target != null && target > 1 -> {
                    "$count of $target this week"
                }

                headline == 0 -> {
                    "no chain yet"
                }

                else -> {
                    "$headline ${plural(headline, unit)}"
                }
            }

    /**
     * Whether the row should be drawn as in danger *right now*.
     *
     * A period already satisfied is not at risk however the last one settled, so a habit
     * ticked at breakfast stops shouting for the rest of the day - a warning that survives
     * the thing that resolved it is the fastest way to teach someone to ignore warnings.
     */
    val isAtRiskNow: Boolean get() = atRisk && !isPaused && !isSatisfied

    private val unit: String get() = if (isWeekly) "week" else "day"

    private fun plural(
        n: Int,
        unit: String,
    ): String = if (n == 1) unit else "${unit}s"
}

/**
 * Every active habit, evaluated as of [today].
 *
 * The whole entry history is loaded rather than a trailing window: lifetime stats run from
 * `habit.createdOn`, so a windowed query would invent misses for anything older than the
 * window. A personal tracker's entry table stays small enough that this is cheap.
 */
fun HabitRepository.observeWidgetRows(today: LocalDate): Flow<List<WidgetRow>> =
    observeHabitData(HabitEvaluator.BEGINNING_OF_TIME).map { data ->
        data.map { it.habit.toWidgetRow(it.entries, it.pauses, today) }
    }

private fun Habit.toWidgetRow(
    entries: List<Entry>,
    pauses: List<Pause>,
    today: LocalDate,
): WidgetRow {
    val timeline = HabitEvaluator.timeline(this, entries, today, pauses)
    val stats = timeline.stats
    return WidgetRow(
        id = id,
        name = name,
        state = timeline.current?.state ?: CellState.PENDING,
        count = timeline.currentCount,
        target = (cadence as? Cadence.TimesPerWeek)?.target,
        headline = stats.headlineFor(this),
        isWeekly = isWeekly,
        polarity = polarity,
        isPaused = pauses.any { it.isOpenEnded },
        pauseReason = pauses.firstOrNull { it.isOpenEnded }?.reason,
        atRisk = stats.atRisk,
    )
}
