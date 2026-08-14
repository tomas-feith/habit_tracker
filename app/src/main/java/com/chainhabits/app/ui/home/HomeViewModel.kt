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
import kotlinx.coroutines.flow.combine
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

    /** "days" or "weeks", singular when the count is one. */
    val headlineUnit: String
        get() {
            val unit = if (habit.isWeekly) "week" else "day"
            return if (headlineCount == 1) unit else "${unit}s"
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

    /**
     * Habit ids in the order the user is currently dragging them into.
     *
     * Applied optimistically so the list follows the finger, and only written to the
     * database when the drag ends - persisting on every swap would round-trip through
     * Room mid-gesture and fight the animation.
     */
    private val orderOverride = MutableStateFlow<List<Long>?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> =
        combine(
            today.flatMapLatest { day ->
                // All of it, not a trailing window: lifetime stats are computed from
                // habit.createdOn, so a windowed query would invent misses for any habit
                // older than the window. A personal tracker's entry table stays tiny.
                repository
                    .observeHabitsWithEntries(HabitEvaluator.BEGINNING_OF_TIME)
                    .map { it to day }
            },
            orderOverride,
        ) { (pairs, day), order ->
            buildState(pairs, day, order)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            HomeUiState(),
        )

    private fun buildState(
        pairs: List<Pair<Habit, List<Entry>>>,
        day: LocalDate,
        order: List<Long>?,
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
        return HomeUiState(rows = rows.inOrder(order), today = day, loaded = true)
    }

    /** Habits not named in [order] (a brand new one, say) keep their stored position. */
    private fun List<HabitRowState>.inOrder(order: List<Long>?): List<HabitRowState> {
        if (order == null) return this
        return sortedBy { row ->
            order.indexOf(row.habit.id).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
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

    /** Moves the dragged habit to the position currently held by [toKey]. */
    fun moveHabit(
        fromKey: Long,
        toKey: Long,
    ) {
        val current = orderOverride.value ?: uiState.value.rows.map { it.habit.id }
        val from = current.indexOf(fromKey)
        val to = current.indexOf(toKey)
        if (from < 0 || to < 0 || from == to) return

        orderOverride.value =
            current.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Writes the dragged order to the database once the gesture finishes. */
    fun commitOrder() =
        viewModelScope.launch {
            orderOverride.value?.let { repository.applyOrder(it) }
        }

    companion object {
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
