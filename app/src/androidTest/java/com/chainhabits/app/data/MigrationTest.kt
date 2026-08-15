package com.chainhabits.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Guards the upgrade path for a database that holds the only copy of your history.
 *
 * There is no server to re-sync from and destructive fallback is deliberately disabled,
 * so a missing or wrong migration means either a crash on launch or silently lost months
 * of tracking. This test is what makes that a build failure instead.
 *
 * When you add version n+1, add a `migrate_n_to_n plus 1` case below. The helper replays
 * the committed schema JSON for the old version, applies the real migration, and then
 * validates the result against the new schema JSON - so a migration whose SQL disagrees
 * with the entity definitions fails here rather than on a device.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HabitDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    /**
     * Opening at the current version must succeed and leave a usable schema.
     *
     * Proves the exported schema JSON exists and matches the entities. Without the export,
     * every migration test would have nothing to diff against.
     */
    @Test
    @Throws(IOException::class)
    fun currentSchemaOpensCleanly() {
        helper.createDatabase(TEST_DB, HabitDatabase.VERSION).use { db ->
            assertTrue("habits table missing", db.hasTable("habits"))
            assertTrue("entries table missing", db.hasTable("entries"))
            assertTrue("pauses table missing", db.hasTable("pauses"))
        }
    }

    /**
     * Adding `pauses` must not disturb what is already there.
     *
     * The database holds the only copy of the user's history and destructive fallback is
     * disabled, so the real risk in an additive migration is not that the new table is
     * missing - it is that the existing rows are damaged on the way past. This writes a
     * habit and an entry at version 1, migrates, and reads them back.
     */
    @Test
    @Throws(IOException::class)
    fun migrate1To2KeepsExistingData() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO habits
                    (id, name, polarity, strictness, cadenceType, cadenceDays,
                     cadenceTarget, reminder_minute_of_day, createdOn, archivedOn, sortOrder)
                VALUES (1, 'Read', 'POSITIVE', 'STANDARD', 'DAILY', 0, 1, NULL, 20000, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL("INSERT INTO entries (habitId, date, count) VALUES (1, 20001, 2)")
        }

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                2,
                true,
                *HabitDatabase.MIGRATIONS.toTypedArray(),
            )

        db.use {
            it.query("SELECT name FROM habits WHERE id = 1").use { cursor ->
                assertTrue("habit row lost in migration", cursor.moveToFirst())
                assertEquals("Read", cursor.getString(0))
            }
            it.query("SELECT count FROM entries WHERE habitId = 1 AND date = 20001").use { cursor ->
                assertTrue("entry row lost in migration", cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            it.query("SELECT COUNT(*) FROM pauses").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("a fresh pauses table must start empty", 0, cursor.getInt(0))
            }
        }
    }

    /**
     * Adding `note` must leave every existing habit intact, with a null note.
     *
     * An `ALTER TABLE ... ADD COLUMN` is the one migration shape that looks too trivial to
     * test, which is exactly why it gets one: the failure mode is not the column being
     * absent, it is the column arriving NOT NULL (or with a default) and the upgrade
     * aborting on a device holding the only copy of the user's history.
     */
    @Test
    @Throws(IOException::class)
    fun migrate2To3AddsANullNote() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO habits
                    (id, name, polarity, strictness, cadenceType, cadenceDays,
                     cadenceTarget, reminder_minute_of_day, createdOn, archivedOn, sortOrder)
                VALUES (1, 'Read', 'POSITIVE', 'STANDARD', 'DAILY', 0, 1, NULL, 20000, NULL, 0)
                """.trimIndent(),
            )
        }

        val db =
            helper.runMigrationsAndValidate(
                TEST_DB,
                3,
                true,
                *HabitDatabase.MIGRATIONS.toTypedArray(),
            )

        db.use {
            it.query("SELECT name, note FROM habits WHERE id = 1").use { cursor ->
                assertTrue("habit row lost in migration", cursor.moveToFirst())
                assertEquals("Read", cursor.getString(0))
                assertTrue("an existing habit must migrate to no note", cursor.isNull(1))
            }
        }
    }

    /**
     * Every migration in [HabitDatabase.MIGRATIONS] must be contiguous and land exactly
     * on the declared version. A gap here would fail at runtime on a real upgrade.
     */
    @Test
    fun migrationsCoverEveryVersion() {
        val migrations = HabitDatabase.MIGRATIONS.sortedBy { it.startVersion }

        var version = 1
        migrations.forEach { migration ->
            assertEquals(
                "migration ${migration.startVersion}->${migration.endVersion} " +
                    "does not follow version $version",
                version,
                migration.startVersion,
            )
            version = migration.endVersion
        }

        assertEquals(
            "MIGRATIONS must reach the declared database version",
            HabitDatabase.VERSION,
            version,
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.hasTable(name: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name))
            .use { it.count > 0 }
}
