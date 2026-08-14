package com.chainhabits.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chainhabits.app.data.HabitRepository
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

data class DetailUiState(
    val habit: Habit? = null,
    val stats: HabitStats? = null,
    /** Day-level cells for the year heatmap. */
    val yearCells: List<Cell> = emptyList(),
    /** Period-level cells, matching the home screen's language. */
    val periodCells: List<Cell> = emptyList(),
    val months: List<MonthBar> = emptyList(),
)

class DetailViewModel(
    private val repository: HabitRepository,
    habitId: Long,
) : ViewModel() {
    /** Bumped on resume so a screen left open past midnight rolls over. */
    private val today = MutableStateFlow(LocalDate.now())

    val uiState: StateFlow<DetailUiState> =
        combine(
            repository.observeHabit(habitId),
            repository.observeEntriesFor(habitId),
            today,
        ) { habit, entries, day ->
            if (habit == null) return@combine DetailUiState()

            val timeline = HabitEvaluator.timeline(habit, entries, day)
            DetailUiState(
                habit = habit,
                stats = timeline.stats,
                yearCells = timeline.dayCellsSince(day.minusDays(YEAR_OF_DAYS)),
                periodCells = timeline.since(HabitEvaluator.inlineWindowStart(habit, day)),
                months = monthlyRates(timeline, day),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DetailUiState())

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
