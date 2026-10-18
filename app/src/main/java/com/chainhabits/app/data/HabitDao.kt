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

    @Query("SELECT * FROM past_goals ORDER BY habitId, lastDay")
    fun observeAllPastGoals(): Flow<List<PastGoalEntity>>

    @Query("SELECT * FROM past_goals WHERE habitId = :habitId ORDER BY lastDay")
    fun observePastGoalsFor(habitId: Long): Flow<List<PastGoalEntity>>

    @Query("SELECT * FROM past_goals WHERE habitId = :habitId ORDER BY lastDay")
    suspend fun pastGoalsFor(habitId: Long): List<PastGoalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPastGoal(goal: PastGoalEntity): Long

    @Query("DELETE FROM past_goals WHERE habitId = :habitId")
    suspend fun deletePastGoalsFor(habitId: Long)

    /**
     * Saves an edited habit together with the goal history that edit produced.
     *
     * One transaction: the habit's new goal and the record of its old one only make sense
     * together, and an observer that saw the first without the second would briefly re-judge
     * the whole history against the new goal - the very flash of misses this prevents.
     */
    @Transaction
    suspend fun updateHabitWithGoals(
        habit: HabitEntity,
        pastGoals: List<PastGoalEntity>,
    ) {
        updateHabit(habit)
        deletePastGoalsFor(habit.id)
        pastGoals.forEach { insertPastGoal(it) }
    }

    @Delete
    suspend fun deleteHabit(habit: HabitEntity)

    @Query("SELECT * FROM entries WHERE date >= :from")
    fun observeEntriesSince(from: LocalDate): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE habitId = :habitId ORDER BY date")
    fun observeEntriesFor(habitId: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE habitId = :habitId AND date >= :from ORDER BY date")
    suspend fun entriesFor(
        habitId: Long,
        from: LocalDate,
    ): List<EntryEntity>

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

    // --- backup ---
    //
    // Deliberately unfiltered: archived habits are history too, and an export that quietly
    // dropped them would restore a library missing everything the user had finished with.

    @Query("SELECT * FROM habits ORDER BY id")
    suspend fun allHabits(): List<HabitEntity>

    @Query("SELECT * FROM entries ORDER BY habitId, date")
    suspend fun allEntries(): List<EntryEntity>

    @Query("SELECT * FROM pauses ORDER BY habitId, start")
    suspend fun allPauses(): List<PauseEntity>

    @Query("SELECT * FROM past_goals ORDER BY habitId, lastDay")
    suspend fun allPastGoals(): List<PastGoalEntity>

    @Query("DELETE FROM habits")
    suspend fun deleteAllHabits()

    /**
     * Replace everything, for a restore.
     *
     * One transaction, so a failure part way cannot leave half a history behind: either the
     * whole file lands or the previous contents are still there. Deleting the habits
     * cascades to entries, pauses and past goals, so no orphan rows survive.
     */
    @Transaction
    suspend fun replaceAll(
        habits: List<HabitEntity>,
        entries: List<EntryEntity>,
        pauses: List<PauseEntity>,
        pastGoals: List<PastGoalEntity>,
    ) {
        deleteAllHabits()
        habits.forEach { insertHabit(it) }
        entries.forEach { upsertEntry(it) }
        pauses.forEach { insertPause(it) }
        pastGoals.forEach { insertPastGoal(it) }
    }

    @Query("SELECT * FROM pauses ORDER BY start")
    fun observeAllPauses(): Flow<List<PauseEntity>>

    @Query("SELECT * FROM pauses WHERE habitId = :habitId ORDER BY start")
    fun observePausesFor(habitId: Long): Flow<List<PauseEntity>>

    @Query("SELECT * FROM pauses WHERE habitId = :habitId ORDER BY start")
    suspend fun pausesFor(habitId: Long): List<PauseEntity>

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
     *
     * An already-running pause keeps its start date, and keeps its own [reason] unless
     * [rewriteReason] says the user has just written a new one. Rewriting unconditionally
     * meant that pausing two more habits under "On vacation" silently retitled the habit
     * that had been paused for a fortnight with "Injured leg".
     */
    @Transaction
    suspend fun startPause(
        habitId: Long,
        from: LocalDate,
        reason: String? = null,
        rewriteReason: Boolean = true,
    ) {
        val open = openPauseFor(habitId)
        if (open != null) {
            if (rewriteReason && open.reason != reason) updatePause(open.copy(reason = reason))
            return
        }
        insertPause(PauseEntity(habitId = habitId, start = from, end = null, reason = reason))
    }

    /**
     * Ends the running pause for [habitId], so the habit is live again from [on] onward.
     *
     * [end] is inclusive, so the pause closes on the day *before* the resume: resuming is
     * the user saying "count me from today", and a pause that still covered today would
     * leave the habit reading as paused for the rest of the day it was un-paused on.
     *
     * A pause that ends up covering no day at all - paused and resumed the same day, the
     * mis-tap this mostly exists for - is deleted rather than stored as an inverted range.
     * That is the one case where dropping the row loses nothing: an empty pause is not
     * history, and keeping it would dash out a day the user never actually paused.
     */
    @Transaction
    suspend fun endPause(
        habitId: Long,
        on: LocalDate,
    ) {
        val open = openPauseFor(habitId) ?: return
        val last = on.minusDays(1)
        if (last < open.start) deletePause(open) else updatePause(open.copy(end = last))
    }

    /**
     * Applies one pass of the pause picker: [pause] suspended, [resume] made live again.
     *
     * One transaction so the list cannot be observed half-applied, which on a screen that
     * redraws every row from the same query would show as a visible flicker of habits
     * changing state one at a time.
     */
    @Transaction
    suspend fun setPaused(
        pause: Collection<Long>,
        resume: Collection<Long>,
        on: LocalDate,
        reason: String? = null,
        rewriteReason: Boolean = true,
    ) {
        pause.forEach { startPause(it, on, reason, rewriteReason) }
        resume.forEach { endPause(it, on) }
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
