package com.chainhabits.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Update
    suspend fun updateHabit(habit: HabitEntity)

    @Delete
    suspend fun deleteHabit(habit: HabitEntity)

    @Query("SELECT * FROM entries WHERE date >= :from")
    fun observeEntriesSince(from: LocalDate): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE habitId = :habitId ORDER BY date")
    fun observeEntriesFor(habitId: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE habitId = :habitId AND date = :date")
    suspend fun getEntry(habitId: Long, date: LocalDate): EntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE habitId = :habitId AND date = :date")
    suspend fun deleteEntry(habitId: Long, date: LocalDate)

    /**
     * Records one event, or removes it if [toggleOff] and an event already exists.
     *
     * Daily habits toggle; times-per-week habits accumulate, so a fourth workout in a
     * 3x week still records rather than silently undoing the third.
     */
    suspend fun logEvent(habitId: Long, date: LocalDate, toggleOff: Boolean, delta: Int = 1) {
        val existing = getEntry(habitId, date)
        val next = (existing?.count ?: 0) + delta
        when {
            toggleOff && existing != null && delta > 0 -> deleteEntry(habitId, date)
            next <= 0 -> deleteEntry(habitId, date)
            else -> upsertEntry(EntryEntity(habitId, date, next))
        }
    }
}
