package com.tsfeith.habits.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tsfeith.habits.data.HabitRepository
import com.tsfeith.habits.domain.Cell
import com.tsfeith.habits.domain.Habit
import com.tsfeith.habits.domain.HabitEvaluator
import com.tsfeith.habits.domain.HabitStats
import com.tsfeith.habits.domain.Timeline
import com.tsfeith.habits.domain.completionRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One bar of the six-month chart. */
data class MonthBar(
    val label: String,
    val rate: Float,
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
        (MONTHS_SHOWN - 1 downTo 0).map { back ->
            val monthStart = today.withDayOfMonth(1).minusMonths(back.toLong())
            val monthEnd = minOf(monthStart.plusMonths(1).minusDays(1), today)
            MonthBar(
                label = monthStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                rate = timeline.between(monthStart, monthEnd).completionRate(),
            )
        }

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
