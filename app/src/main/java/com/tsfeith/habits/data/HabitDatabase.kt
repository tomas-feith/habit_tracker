package com.tsfeith.habits.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate

class Converters {
    /** Stored as an epoch day so date ranges sort and compare correctly in SQL. */
    @TypeConverter
    fun toDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromDate(date: LocalDate?): Long? = date?.toEpochDay()
}

@Database(
    entities = [HabitEntity::class, EntryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        @Volatile
        private var instance: HabitDatabase? = null

        fun get(context: Context): HabitDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HabitDatabase::class.java,
                    "habits.db",
                ).build().also { instance = it }
            }
    }
}
