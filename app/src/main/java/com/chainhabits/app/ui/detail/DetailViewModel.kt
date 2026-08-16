package com.chainhabits.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.domain.Backfill
import com.chainhabits.app.domain.Cell
import com.chainhabits.app.domain.CellState
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.HabitEvaluator
import com.chainhabits.app.domain.HabitStats
import com.chainhabits.app.domain.Timeline
import com.chainhabits.app.domain.completionRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One bar of the six-month chart.
 *
 * [hasData] separates "you did nothing that month" from "the habit did not exist yet";
 * only the first deserves an empty bar.
 */
data class MonthBar(
    val label: String,
    val rate: Float,
    val hasData: Boolean,
)

/**
 * The day the user tapped in the heatmap, as the correction sheet needs it.
 *
 * Derived from the live timeline on every emission rather than captured at tap time, so
 * editing the day updates the open sheet instead of leaving it showing a stale count.
 */
data class DaySelection(
    val date: LocalDate,
    val count: Int,
    val state: CellState,
    /** False once the day has settled past the backfill window, or was never scheduled. */
    val editable: Boolean,
)

data class DetailUiState(
    val habit: Habit? = null,
    val stats: HabitStats? = null,
    /** Day-level cells for the year heatmap. */
    val yearCells: List<Cell> = emptyList(),
    /** Period-level cells, matching the home screen's language. */
    val periodCells: List<Cell> = emptyList(),
    val months: List<MonthBar> = emptyList(),
    /** True while a pause is running. */
    val isPaused: Boolean = false,
    /** The day whose correction sheet is open, if any. */
    val selectedDay: DaySelection? = null,
)

class DetailViewModel(
    private val repository: HabitRepository,
    private val habitId: Long,
) : ViewModel() {
    /** Bumped on resume so a screen left open past midnight rolls over. */
    private val today = MutableStateFlow(LocalDate.now())

    /** The heatmap day the user tapped. Only the date is held; the rest is re-derived. */
    private val selectedDate = MutableStateFlow<LocalDate?>(null)

    val uiState: StateFlow<DetailUiState> =
        combine(
            repository.observeHabit(habitId),
            repository.observeEntriesFor(habitId),
            repository.observePausesFor(habitId),
            today,
            selectedDate,
        ) { habit, entries, pauses, day, selected ->
            if (habit == null) return@combine DetailUiState()

            val timeline = HabitEvaluator.timeline(habit, entries, day, pauses)
            DetailUiState(
                habit = habit,
                stats = timeline.stats,
                yearCells = timeline.dayCellsSince(day.minusDays(YEAR_OF_DAYS)),
                periodCells = timeline.since(HabitEvaluator.inlineWindowStart(habit, day)),
                months = monthlyRates(timeline, day),
                isPaused = pauses.any { it.isOpenEnded },
                selectedDay = selected?.let { select(habit, timeline, it, day) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

    /**
     * Reads [date] out of the evaluated timeline.
     *
     * A date with no cell - one before the habit existed - reports an empty, uneditable
     * day rather than nothing at all, so a stale selection can't blank the sheet mid-edit.
     */
    private fun select(
        habit: Habit,
        timeline: Timeline,
        date: LocalDate,
        today: LocalDate,
    ): DaySelection {
        val cell = timeline.dayCells.firstOrNull { it.date == date }
        return DaySelection(
            date = date,
            count = cell?.count ?: 0,
            state = cell?.state ?: CellState.NOT_SCHEDULED,
            editable = Backfill.isEditable(habit, date, today),
        )
    }

    fun selectDay(date: LocalDate) {
        selectedDate.value = date
    }

    fun dismissDay() {
        selectedDate.value = null
    }

    /**
     * Writes [count] to the selected day - the fix for a habit done but never logged.
     *
     * Re-checks the window rather than trusting the screen to have hidden the control:
     * the sheet can be sitting open across midnight, at which point its oldest day has
     * quietly aged out from under it.
     */
    fun setSelectedCount(count: Int) =
        viewModelScope.launch {
            val state = uiState.value
            val habit = state.habit ?: return@launch
            val day = state.selectedDay ?: return@launch
            if (!Backfill.isEditable(habit, day.date, today.value)) return@launch
            repository.setEventCount(habit, day.date, count)
        }

    fun setPaused(paused: Boolean) =
        viewModelScope.launch {
            if (paused) {
                repository.pauseHabit(habitId, today.value)
            } else {
                repository.resumeHabit(habitId, today.value)
            }
        }

    fun refreshDate() {
        today.value = LocalDate.now()
    }

    /**
     * Completion rate for each of the last six months, oldest first.
     *
     * Slices the already-evaluated timeline rather than re-evaluating each month, so a
     * past month's final day settles as a real miss instead of being mistaken for an
     * open period.
     */
    private fun monthlyRates(
        timeline: Timeline,
        today: LocalDate,
    ): List<MonthBar> =
        (MONTHS_SHOWN - 1 downTo 0)
            .map { back ->
                val monthStart = today.withDayOfMonth(1).minusMonths(back.toLong())
                val monthEnd = minOf(monthStart.plusMonths(1).minusDays(1), today)
                val cells = timeline.between(monthStart, monthEnd)
                MonthBar(
                    label = monthStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    rate = cells.completionRate(),
                    hasData = cells.any { it.state != CellState.NOT_SCHEDULED },
                )
            }
            // Months before the habit existed have nothing to report. Drawing them as
            // empty 0% bars reads as "you failed all of March", which is a lie.
            .dropWhile { !it.hasData }

    fun archive() =
        viewModelScope.launch {
            uiState.value.habit?.let { repository.archiveHabit(it, today.value) }
        }

    fun delete() =
        viewModelScope.launch {
            uiState.value.habit?.let { repository.deleteHabit(it) }
        }

    private companion object {
        /** 52 whole weeks, so the heatmap's columns line up. */
        const val YEAR_OF_DAYS = 363L
        const val MONTHS_SHOWN = 6
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
