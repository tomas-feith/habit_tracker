package com.chainhabits.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

enum class CadenceType { DAILY, SPECIFIC_DAYS, TIMES_PER_WEEK }

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val polarity: Polarity,
    val strictness: Strictness,
    val cadenceType: CadenceType,
    /** Bitmask of [DayOfWeek.getValue] (Mon=1..Sun=7); only used by SPECIFIC_DAYS. */
    val cadenceDays: Int,
    /** Floor for positive habits, allowance for negative; only used by TIMES_PER_WEEK. */
    val cadenceTarget: Int,
    @ColumnInfo(name = "reminder_minute_of_day") val reminderMinuteOfDay: Int?,
    val createdOn: LocalDate,
    val archivedOn: LocalDate?,
    val sortOrder: Int,
)

/**
 * A logged event. Unique per (habit, date) - repeated logging on one day bumps [count]
 * rather than inserting rows, which is what a "3x per week" habit needs when you do two
 * workouts on a Saturday.
 */
@Entity(
    tableName = "entries",
    primaryKeys = ["habitId", "date"],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("habitId"), Index("date")],
)
data class EntryEntity(
    val habitId: Long,
    val date: LocalDate,
    val count: Int,
)

fun HabitEntity.toDomain(): Habit = Habit(
    id = id,
    name = name,
    polarity = polarity,
    strictness = strictness,
    cadence = when (cadenceType) {
        CadenceType.DAILY -> Cadence.Daily
        CadenceType.SPECIFIC_DAYS -> Cadence.SpecificDays(cadenceDays.toDaySet())
        CadenceType.TIMES_PER_WEEK -> Cadence.TimesPerWeek(cadenceTarget)
    },
    reminderTime = reminderMinuteOfDay?.let { LocalTime.of(it / 60, it % 60) },
    createdOn = createdOn,
    archivedOn = archivedOn,
    sortOrder = sortOrder,
)

fun Habit.toEntity(): HabitEntity = HabitEntity(
    id = id,
    name = name,
    polarity = polarity,
    strictness = strictness,
    cadenceType = when (cadence) {
        is Cadence.Daily -> CadenceType.DAILY
        is Cadence.SpecificDays -> CadenceType.SPECIFIC_DAYS
        is Cadence.TimesPerWeek -> CadenceType.TIMES_PER_WEEK
    },
    cadenceDays = (cadence as? Cadence.SpecificDays)?.days?.toBitmask() ?: 0,
    cadenceTarget = (cadence as? Cadence.TimesPerWeek)?.target ?: 1,
    reminderMinuteOfDay = reminderTime?.let { it.hour * 60 + it.minute },
    createdOn = createdOn,
    archivedOn = archivedOn,
    sortOrder = sortOrder,
)

fun Set<DayOfWeek>.toBitmask(): Int = fold(0) { acc, d -> acc or (1 shl d.value) }

fun Int.toDaySet(): Set<DayOfWeek> =
    DayOfWeek.entries.filter { (this shr it.value) and 1 == 1 }.toSet()
