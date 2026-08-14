package com.tsfeith.habits.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
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
    version = HabitDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        const val VERSION = 1

        const val NAME = "habits.db"

        /**
         * Schema migrations, oldest first.
         *
         * This app holds the only copy of your history - there is no server to re-sync
         * from - so destructive fallback is deliberately never enabled. A missing
         * migration must fail loudly in testing rather than quietly wipe months of data
         * on a real device.
         *
         * To add one:
         *  1. Change the entities and bump [VERSION].
         *  2. Add a `Migration(n, n + 1)` here with the SQL.
         *  3. `MigrationTest` will fail until the schema JSONs and the SQL agree.
         *
         * The exported schema JSONs under `app/schemas` are committed for exactly this
         * reason: they are what the migration test diffs against.
         */
        val MIGRATIONS: List<Migration> = emptyList()

        @Volatile
        private var instance: HabitDatabase? = null

        fun get(context: Context): HabitDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): HabitDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    HabitDatabase::class.java,
                    NAME,
                ).apply { MIGRATIONS.forEach { addMigrations(it) } }
                .build()
    }
}
