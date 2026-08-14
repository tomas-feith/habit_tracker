package com.tsfeith.habits.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tsfeith.habits.data.HabitRepository
import com.tsfeith.habits.domain.Cell
import com.tsfeith.habits.domain.CellState
import com.tsfeith.habits.domain.Entry
import com.tsfeith.habits.domain.Habit
import com.tsfeith.habits.domain.HabitEvaluator
import com.tsfeith.habits.domain.HabitStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MonthBar(val label: String, val rate: Float)

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

    private val today = LocalDate.now()

    val uiState: StateFlow<DetailUiState> = combine(
        repository.observeHabit(habitId),
        repository.observeEntriesFor(habitId),
    ) { habit, entries ->
        if (habit == null) return@combine DetailUiState()

        val yearStart = today.minusDays(363)
        DetailUiState(
            habit = habit,
            stats = HabitEvaluator.stats(habit, entries, today),
            yearCells = HabitEvaluator.dayLevelCells(habit, entries, yearStart, today),
            periodCells = HabitEvaluator.cells(
                habit,
                entries,
                HabitEvaluator.inlineWindowStart(habit, today),
                today,
            ),
            months = monthlyRates(habit, entries),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    /** Completion rate for each of the last six months, oldest first. */
    private fun monthlyRates(habit: Habit, entries: List<Entry>): List<MonthBar> =
        (5 downTo 0).map { back ->
        val monthStart = today.withDayOfMonth(1).minusMonths(back.toLong())
        val monthEnd = minOf(monthStart.plusMonths(1).minusDays(1), today)
        val cells = HabitEvaluator.cells(habit, entries, monthStart, monthEnd)

        val judged = cells.count {
            it.state == CellState.DONE ||
                it.state == CellState.MISSED_ONCE ||
                it.state == CellState.BROKEN
        }
        val good = cells.count { it.state == CellState.DONE }

        MonthBar(
            label = monthStart.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
            rate = if (judged == 0) 0f else good.toFloat() / judged,
        )
    }

    fun archive() = viewModelScope.launch {
        uiState.value.habit?.let { repository.archiveHabit(it, today) }
    }

    fun delete() = viewModelScope.launch {
        uiState.value.habit?.let { repository.deleteHabit(it) }
    }
}
