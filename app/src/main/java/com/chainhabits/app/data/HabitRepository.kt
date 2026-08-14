package com.chainhabits.app.data

import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Entry
import com.chainhabits.app.domain.Habit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class HabitRepository(
    private val dao: HabitDao,
    /**
     * Called after any write, so out-of-process views can be refreshed.
     *
     * The home-screen widget renders to `RemoteViews` in the launcher's process, which no
     * Room `Flow` reaches - it has to be pushed at. Kept as a plain lambda rather than a
     * Context or a widget reference so the data layer stays free of Android UI types and
     * the repository is still constructible in a unit test.
     */
    private val onDataChanged: suspend () -> Unit = {},
) {
    fun observeHabits(): Flow<List<Habit>> =
        dao.observeActiveHabits().map { list -> list.map { it.toDomain() } }

    fun observeHabit(id: Long): Flow<Habit?> = dao.observeHabit(id).map { it?.toDomain() }

    fun observeEntriesFor(habitId: Long): Flow<List<Entry>> =
        dao.observeEntriesFor(habitId).map { list ->
            list.map { Entry(it.habitId, it.date, it.count) }
        }

    /** Habits paired with the entries needed to draw their inline mosaic. */
    fun observeHabitsWithEntries(since: LocalDate): Flow<List<Pair<Habit, List<Entry>>>> =
        combine(observeHabits(), dao.observeEntriesSince(since)) { habits, entries ->
            val byHabit = entries.groupBy { it.habitId }
            habits.map { habit ->
                habit to (
                    byHabit[habit.id]
                        .orEmpty()
                        .map { Entry(it.habitId, it.date, it.count) }
                )
            }
        }

    suspend fun addHabit(habit: Habit): Long =
        dao.insertHabit(habit.toEntity()).also { onDataChanged() }

    suspend fun updateHabit(habit: Habit) {
        dao.updateHabit(habit.toEntity())
        onDataChanged()
    }

    suspend fun deleteHabit(habit: Habit) {
        dao.deleteHabit(habit.toEntity())
        onDataChanged()
    }

    suspend fun archiveHabit(
        habit: Habit,
        on: LocalDate,
    ) {
        dao.updateHabit(habit.copy(archivedOn = on).toEntity())
        onDataChanged()
    }

    /**
     * Records the habit's event for [date] - a completion for a positive habit, a slip
     * for a negative one.
     *
     * Daily habits toggle, so tapping an already-done habit undoes it. Times-per-week
     * habits accumulate instead: logging a fourth workout in a 3x week should count as a
     * fourth workout, not undo the third. Use [removeEvent] to correct those.
     */
    suspend fun logEvent(
        habit: Habit,
        date: LocalDate,
    ) {
        dao.logEvent(habit.id, date, toggleOff = habit.cadence !is Cadence.TimesPerWeek)
        onDataChanged()
    }

    suspend fun removeEvent(
        habit: Habit,
        date: LocalDate,
    ) {
        dao.logEvent(habit.id, date, toggleOff = false, delta = -1)
        onDataChanged()
    }

    /** Persists a new habit ordering, given the ids in their final order. */
    suspend fun applyOrder(idsInOrder: List<Long>) {
        dao.applyOrder(idsInOrder)
        onDataChanged()
    }

    suspend fun getHabit(id: Long): Habit? = dao.getHabit(id)?.toDomain()

    suspend fun habitsWithReminders(): List<Habit> = dao.habitsWithReminders().map { it.toDomain() }
}
