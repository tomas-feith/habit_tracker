package com.chainhabits.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chainhabits.app.HabitApplication
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Cell
import com.chainhabits.app.domain.Entry
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.HabitEvaluator
import com.chainhabits.app.domain.HabitStats
import com.chainhabits.app.domain.Strictness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HabitRowState(
    val habit: Habit,
    val cells: List<Cell>,
    val stats: HabitStats,
    /** Events logged in the current period - "2 of 3 this week", or today's count. */
    val currentCount: Int,
) {
    val weeklyTarget: Int? get() = (habit.cadence as? Cadence.TimesPerWeek)?.target

    /** Whether the habit's bar for the current period is already met. */
    val isSatisfied: Boolean
        get() = weeklyTarget?.let { currentCount >= it } ?: (currentCount > 0)

    /**
     * The number the row leads with. Strict habits lead with the honest streak, where
     * "days since" is the whole point; standard habits lead with the chain, so one sick
     * day doesn't wipe the board and take the motivation with it.
     */
    val headlineCount: Int
        get() =
            if (habit.strictness == Strictness.STRICT) {
                stats.currentStreak
            } else {
                stats.chainLength
            }
}

data class HomeUiState(
    val rows: List<HabitRowState> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
) {
    /** Habits one miss away from a broken chain. Drives the never-miss-twice banner. */
    val atRisk: List<HabitRowState> get() = rows.filter { it.stats.atRisk }
}

class HomeViewModel(
    private val repository: HabitRepository,
) : ViewModel() {
    /** Bumped on resume so the app rolls over correctly if left open past midnight. */
    private val today = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> =
        today
            .flatMapLatest { day ->
                // All of it, not a trailing window: lifetime stats are computed from
                // habit.createdOn, so a windowed query would invent misses for any habit
                // older than the window. A personal tracker's entry table stays tiny.
                repository
                    .observeHabitsWithEntries(BEGINNING_OF_TIME)
                    .map { pairs -> buildState(pairs, day) }
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                HomeUiState(),
            )

    private fun buildState(
        pairs: List<Pair<Habit, List<Entry>>>,
        day: LocalDate,
    ): HomeUiState {
        val rows =
            pairs.map { (habit, entries) ->
                // Evaluate the whole history once, then slice. Evaluating the visible window
                // directly would restart the never-miss-twice counter at the window edge.
                val timeline = HabitEvaluator.timeline(habit, entries, day)
                HabitRowState(
                    habit = habit,
                    cells = timeline.since(HabitEvaluator.inlineWindowStart(habit, day)),
                    stats = timeline.stats,
                    currentCount = timeline.currentCount,
                )
            }
        return HomeUiState(rows = rows, today = day, loaded = true)
    }

    fun refreshDate() {
        today.value = LocalDate.now()
    }

    fun logEvent(habit: Habit) =
        viewModelScope.launch {
            repository.logEvent(habit, today.value)
        }

    fun removeEvent(habit: Habit) =
        viewModelScope.launch {
            repository.removeEvent(habit, today.value)
        }

    companion object {
        /**
         * Lower bound for "every entry ever".
         *
         * Deliberately not `LocalDate.EPOCH`, which is API 33 and would crash on
         * anything older - this app supports API 26.
         */
        private val BEGINNING_OF_TIME: LocalDate = LocalDate.ofEpochDay(0)

        /** How long the flow stays warm after the screen goes away. */
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory =
            viewModelFactory {
                initializer {
                    val app =
                        this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                            as HabitApplication
                    HomeViewModel(app.repository)
                }
            }
    }
}
