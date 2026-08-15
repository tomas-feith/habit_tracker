package com.chainhabits.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate

class Converters {
    /** Stored as an epoch day so date ranges sort and compare correctly in SQL. */
    @TypeConverter
    fun toDate(value: Long?): LocalDate? = value?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromDate(date: LocalDate?): Long? = date?.toEpochDay()
}

/**
 * Adds the `pauses` table.
 *
 * Purely additive - no existing row is touched - so an upgrade cannot lose history. The
 * column names are quoted because `end` is a SQL keyword.
 */
private val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pauses` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `habitId` INTEGER NOT NULL,
                    `start` INTEGER NOT NULL,
                    `end` INTEGER,
                    FOREIGN KEY(`habitId`) REFERENCES `habits`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_pauses_habitId` ON `pauses` (`habitId`)")
        }
    }

/**
 * Adds the nullable `note` column to `habits`.
 *
 * A nullable ADD COLUMN needs no default and rewrites no row: every existing habit simply
 * has no note, which is exactly what it had before.
 */
private val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `habits` ADD COLUMN `note` TEXT")
        }
    }

@Database(
    entities = [HabitEntity::class, EntryEntity::class, PauseEntity::class],
    version = HabitDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HabitDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao

    companion object {
        const val VERSION = 3

        const val NAME = "habits.db"

        /**
         * Schema migrations, oldest first.
         *
         * Android's backup restores this same database rather than rebuilding it from a
         * server, so a bad migration corrupts the backup too. Destructive fallback is
         * therefore deliberately never enabled: a missing migration must fail loudly in
         * testing rather than quietly wipe months of data on a real device.
         *
         * To add one:
         *  1. Change the entities and bump [VERSION].
         *  2. Add a `Migration(n, n + 1)` here with the SQL.
         *  3. `MigrationTest` will fail until the schema JSONs and the SQL agree.
         *
         * The exported schema JSONs under `app/schemas` are committed for exactly this
         * reason: they are what the migration test diffs against.
         */
        val MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)

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
