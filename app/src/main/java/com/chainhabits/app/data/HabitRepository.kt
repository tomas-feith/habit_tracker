package com.chainhabits.app.data

import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Entry
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Pause
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    fun observePausesFor(habitId: Long): Flow<List<Pause>> =
        dao.observePausesFor(habitId).map { list -> list.map { it.toDomain() } }

    /** Everything needed to evaluate one habit's timeline. */
    data class HabitData(
        val habit: Habit,
        val entries: List<Entry>,
        val pauses: List<Pause>,
    ) {
        /** True while a pause is running, which is what the UI offers "Resume" for. */
        val isPaused: Boolean get() = pauses.any { it.isOpenEnded }
    }

    /** Habits with the entries and pauses needed to draw and judge their mosaic. */
    fun observeHabitData(since: LocalDate): Flow<List<HabitData>> =
        combine(
            observeHabits(),
            dao.observeEntriesSince(since),
            dao.observeAllPauses(),
        ) { habits, entries, pauses ->
            val entriesByHabit = entries.groupBy { it.habitId }
            val pausesByHabit = pauses.groupBy { it.habitId }
            habits.map { habit ->
                HabitData(
                    habit = habit,
                    entries =
                        entriesByHabit[habit.id]
                            .orEmpty()
                            .map { Entry(it.habitId, it.date, it.count) },
                    pauses = pausesByHabit[habit.id].orEmpty().map { it.toDomain() },
                )
            }
        }

    /**
     * Creates [habit], placing it at the end of the list.
     *
     * The position is assigned here rather than taken from [habit]: the edit form has no
     * concept of sort order, so anything it builds carries the default 0. See
     * [nextSortOrder] for why that is the wrong place to land.
     */
    suspend fun addHabit(habit: Habit): Long {
        val entity = habit.toEntity().copy(sortOrder = nextSortOrder(dao.allHabits()))
        return dao.insertHabit(entity).also { onDataChanged() }
    }

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

    /**
     * Sets what [date] holds for [habit], for correcting a day that was never logged.
     *
     * Absolute rather than relative, unlike [logEvent] and [removeEvent]: the caller is
     * editing a day it is already showing the user, so it knows the number it wants and
     * shouldn't have to reason about whether this cadence toggles or accumulates. Zero
     * deletes the row rather than storing it, so "never logged" stays a single state.
     *
     * Enforcing *which* days may be corrected is [com.chainhabits.app.domain.Backfill]'s
     * job, not this one's - a restore has to be able to write any date at all.
     */
    suspend fun setEventCount(
        habit: Habit,
        date: LocalDate,
        count: Int,
    ) {
        if (count <= 0) {
            dao.deleteEntry(habit.id, date)
        } else {
            dao.upsertEntry(EntryEntity(habit.id, date, count))
        }
        onDataChanged()
    }

    /**
     * Suspends [habitId] from [from] onward. Idempotent - pausing twice changes nothing.
     *
     * Paused periods settle as not-scheduled, so they neither count as misses nor break a
     * chain: a fortnight away is not a fortnight of failure.
     */
    suspend fun pauseHabit(
        habitId: Long,
        from: LocalDate,
    ) {
        dao.startPause(habitId, from)
        onDataChanged()
    }

    /** Ends the running pause for [habitId] on [on]. No-op if it was not paused. */
    suspend fun resumeHabit(
        habitId: Long,
        on: LocalDate,
    ) {
        dao.endPause(habitId, on)
        onDataChanged()
    }

    /**
     * Pauses every active habit - the "I'm going on holiday" button.
     *
     * Already-paused habits are left exactly as they are rather than being restarted, so
     * a habit paused since last week keeps its real start date.
     */
    suspend fun pauseAll(from: LocalDate) {
        dao.observeActiveHabits().first().forEach { dao.startPause(it.id, from) }
        onDataChanged()
    }

    /** Ends every running pause. */
    suspend fun resumeAll(on: LocalDate) {
        dao.pausedHabitIds().forEach { dao.endPause(it, on) }
        onDataChanged()
    }

    /** Persists a new habit ordering, given the ids in their final order. */
    suspend fun applyOrder(idsInOrder: List<Long>) {
        dao.applyOrder(idsInOrder)
        onDataChanged()
    }

    suspend fun getHabit(id: Long): Habit? = dao.getHabit(id)?.toDomain()

    /**
     * One habit's entries from [from] onward, as a snapshot.
     *
     * The Flow variants above are for the UI, which stays subscribed. A reminder firing in
     * a BroadcastReceiver asks once and is gone, and [from] keeps it from reading a year of
     * history to answer a question about this week.
     */
    suspend fun entriesFor(
        habitId: Long,
        from: LocalDate,
    ): List<Entry> = dao.entriesFor(habitId, from).map { Entry(it.habitId, it.date, it.count) }

    suspend fun pausesFor(habitId: Long): List<Pause> = dao.pausesFor(habitId).map { it.toDomain() }

    suspend fun habitsWithReminders(): List<Habit> = dao.habitsWithReminders().map { it.toDomain() }

    // --- backup ---

    /** Everything the app stores, as a transfer file. */
    suspend fun exportBackup(exportedAt: String): String =
        buildBackup(dao.allHabits(), dao.allEntries(), dao.allPauses(), exportedAt)

    /**
     * Replace everything from a backup file.
     *
     * Replace rather than merge: a restore is a restore, and merging would have to invent an
     * answer for a habit present in both with different history. Ids are preserved so
     * entries and pauses still point at their habit, which is also why the whole thing has
     * to go in one transaction.
     */
    suspend fun restoreBackup(text: String): RestoreResult {
        val result = parseBackup(text)
        if (result is RestoreResult.Success) {
            dao.replaceAll(result.habits, result.entries, result.pauses)
            onDataChanged()
        }
        return result
    }
}
