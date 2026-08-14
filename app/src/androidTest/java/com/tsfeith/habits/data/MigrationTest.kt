package com.tsfeith.habits.data

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
     * At version 1 there is nothing to migrate, so this is the meaningful assertion:
     * it proves the exported schema JSON exists and matches the entities. Without the
     * export, every future migration test would have nothing to diff against.
     */
    @Test
    @Throws(IOException::class)
    fun currentSchemaOpensCleanly() {
        helper.createDatabase(TEST_DB, HabitDatabase.VERSION).use { db ->
            assertTrue("habits table missing", db.hasTable("habits"))
            assertTrue("entries table missing", db.hasTable("entries"))
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
