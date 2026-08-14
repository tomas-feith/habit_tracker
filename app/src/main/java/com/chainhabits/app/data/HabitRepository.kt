package com.chainhabits.app.data

import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Entry
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

class HabitRepository(private val dao: HabitDao) {

    fun observeHabits(): Flow<List<Habit>> =
        dao.observeActiveHabits().map { list -> list.map { it.toDomain() } }

    fun observeHabit(id: Long): Flow<Habit?> =
        dao.observeHabit(id).map { it?.toDomain() }

    fun observeEntriesFor(habitId: Long): Flow<List<Entry>> =
        dao.observeEntriesFor(habitId).map { list ->
            list.map { Entry(it.habitId, it.date, it.count) }
        }

    /** Habits paired with the entries needed to draw their inline mosaic. */
    fun observeHabitsWithEntries(since: LocalDate): Flow<List<Pair<Habit, List<Entry>>>> =
        combine(observeHabits(), dao.observeEntriesSince(since)) { habits, entries ->
            val byHabit = entries.groupBy { it.habitId }
            habits.map { habit ->
                habit to (byHabit[habit.id].orEmpty()
                    .map { Entry(it.habitId, it.date, it.count) })
            }
        }

    suspend fun addHabit(habit: Habit): Long = dao.insertHabit(habit.toEntity())

    suspend fun updateHabit(habit: Habit) = dao.updateHabit(habit.toEntity())

    suspend fun deleteHabit(habit: Habit) = dao.deleteHabit(habit.toEntity())

    suspend fun archiveHabit(habit: Habit, on: LocalDate) =
        dao.updateHabit(habit.copy(archivedOn = on).toEntity())

    /**
     * Records the habit's event for [date] - a completion for a positive habit, a slip
     * for a negative one.
     *
     * Daily habits toggle, so tapping an already-done habit undoes it. Times-per-week
     * habits accumulate instead: logging a fourth workout in a 3x week should count as a
     * fourth workout, not undo the third. Use [removeEvent] to correct those.
     */
    suspend fun logEvent(habit: Habit, date: LocalDate) =
        dao.logEvent(habit.id, date, toggleOff = habit.cadence !is Cadence.TimesPerWeek)

    suspend fun removeEvent(habit: Habit, date: LocalDate) =
        dao.logEvent(habit.id, date, toggleOff = false, delta = -1)

    suspend fun getHabit(id: Long): Habit? = dao.getHabit(id)?.toDomain()

    suspend fun habitsWithReminders(): List<Habit> =
        dao.habitsWithReminders().map { it.toDomain() }
}

/** Label for the action that logs an event, which differs by polarity. */
val Habit.logActionLabel: String
    get() = when (polarity) {
        Polarity.POSITIVE -> "Done"
        Polarity.NEGATIVE -> "I slipped"
    }
