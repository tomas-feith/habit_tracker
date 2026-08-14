package com.chainhabits.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE archivedOn IS NULL ORDER BY sortOrder, id")
    fun observeActiveHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE id = :id")
    fun observeHabit(id: Long): Flow<HabitEntity?>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getHabit(id: Long): HabitEntity?

    @Query("SELECT * FROM habits WHERE archivedOn IS NULL AND reminder_minute_of_day IS NOT NULL")
    suspend fun habitsWithReminders(): List<HabitEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHabit(habit: HabitEntity): Long

    @Query("UPDATE habits SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(
        id: Long,
        order: Int,
    )

    /**
     * Rewrites the whole ordering in one transaction.
     *
     * Writing positions one at a time would let the list observer emit halfway through
     * and briefly render habits in a nonsensical order.
     */
    @Transaction
    suspend fun applyOrder(idsInOrder: List<Long>) {
        idsInOrder.forEachIndexed { index, id -> setSortOrder(id, index) }
    }

    @Update
    suspend fun updateHabit(habit: HabitEntity)

    @Delete
    suspend fun deleteHabit(habit: HabitEntity)

    @Query("SELECT * FROM entries WHERE date >= :from")
    fun observeEntriesSince(from: LocalDate): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE habitId = :habitId ORDER BY date")
    fun observeEntriesFor(habitId: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE habitId = :habitId AND date = :date")
    suspend fun getEntry(
        habitId: Long,
        date: LocalDate,
    ): EntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE habitId = :habitId AND date = :date")
    suspend fun deleteEntry(
        habitId: Long,
        date: LocalDate,
    )

    @Query("SELECT * FROM pauses ORDER BY start")
    fun observeAllPauses(): Flow<List<PauseEntity>>

    @Query("SELECT * FROM pauses WHERE habitId = :habitId ORDER BY start")
    fun observePausesFor(habitId: Long): Flow<List<PauseEntity>>

    /** The running pause for a habit, if any. At most one is ever open at a time. */
    @Query("SELECT * FROM pauses WHERE habitId = :habitId AND `end` IS NULL LIMIT 1")
    suspend fun openPauseFor(habitId: Long): PauseEntity?

    @Query("SELECT habitId FROM pauses WHERE `end` IS NULL")
    suspend fun pausedHabitIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPause(pause: PauseEntity): Long

    @Update
    suspend fun updatePause(pause: PauseEntity)

    @Delete
    suspend fun deletePause(pause: PauseEntity)

    /**
     * Starts a pause for [habitId] on [from], unless one is already running.
     *
     * Transactional and idempotent: two taps, or a "pause everything" that includes an
     * already-paused habit, must not leave two overlapping open pauses behind.
     */
    @Transaction
    suspend fun startPause(
        habitId: Long,
        from: LocalDate,
    ) {
        if (openPauseFor(habitId) != null) return
        insertPause(PauseEntity(habitId = habitId, start = from, end = null))
    }

    /**
     * Ends the running pause for [habitId] on [on].
     *
     * A pause that ends before it began would be nonsense, so a same-day resume collapses to
     * a single paused day rather than an inverted range.
     */
    @Transaction
    suspend fun endPause(
        habitId: Long,
        on: LocalDate,
    ) {
        val open = openPauseFor(habitId) ?: return
        updatePause(open.copy(end = maxOf(on, open.start)))
    }

    /**
     * Records one event, or removes it if [toggleOff] and an event already exists.
     *
     * Daily habits toggle; times-per-week habits accumulate, so a fourth workout in a
     * 3x week still records rather than silently undoing the third.
     *
     * Transactional because it reads the current count before writing it back; without
     * that, two quick taps could both read zero and the second would lose the first.
     */
    @Transaction
    suspend fun logEvent(
        habitId: Long,
        date: LocalDate,
        toggleOff: Boolean,
        delta: Int = 1,
    ) {
        val existing = getEntry(habitId, date)
        val next = (existing?.count ?: 0) + delta
        when {
            toggleOff && existing != null && delta > 0 -> deleteEntry(habitId, date)
            next <= 0 -> deleteEntry(habitId, date)
            else -> upsertEntry(EntryEntity(habitId, date, next))
        }
    }
}
