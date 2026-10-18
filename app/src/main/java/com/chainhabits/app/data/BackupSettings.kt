package com.chainhabits.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Where the scheduled backup writes, and how its last run went.
 *
 * Kept out of the Room database on purpose: none of it is history, and a restore replaces
 * the database wholesale. Restoring a backup must not also switch scheduled backups off.
 *
 * Left out of Android's own backup, which carries only the database (see
 * `res/xml/backup_rules.xml`). A folder grant belongs to one install and does not travel
 * with a restore, so carrying the URI to a new phone would only produce a folder that
 * fails on its first run.
 */
private val Context.backupStore: DataStore<Preferences> by preferencesDataStore(
    name = "scheduled_backup",
)

class BackupSettings(
    private val context: Context,
) {
    private companion object {
        val FOLDER = stringPreferencesKey("backup_folder_uri")
        val LAST_AT = stringPreferencesKey("last_backup_at")
        val LAST_ERROR = stringPreferencesKey("last_backup_error")
    }

    /**
     * The SAF tree the scheduled backup writes into, or null when it is switched off.
     *
     * A URI rather than a path: the app holds a persisted grant on this folder, not
     * filesystem access to it, and the grant is what the URI identifies.
     */
    val folder: Flow<String?> = context.backupStore.data.map { it[FOLDER] }

    val lastBackupAt: Flow<String?> = context.backupStore.data.map { it[LAST_AT] }

    /**
     * Why the last backup failed, or null if it did not.
     *
     * Recorded rather than only logged. A backup running unattended is trusted precisely
     * because nobody is watching it, so the one place it can report a folder that has gone
     * away is the screen the user visits when they wonder whether it is still working.
     */
    val lastError: Flow<String?> = context.backupStore.data.map { it[LAST_ERROR] }

    /** A one-shot read, for the worker. */
    suspend fun currentFolder(): String? = folder.first()

    suspend fun setFolder(uri: String) {
        context.backupStore.edit {
            it[FOLDER] = uri
            // A newly chosen folder has not failed yet, and carrying the previous folder's
            // complaint over to it would be a lie the user cannot act on.
            it.remove(LAST_ERROR)
        }
    }

    suspend fun clearFolder() {
        context.backupStore.edit {
            it.remove(FOLDER)
            it.remove(LAST_AT)
            it.remove(LAST_ERROR)
        }
    }

    suspend fun recordSuccess(timestamp: String) {
        context.backupStore.edit {
            it[LAST_AT] = timestamp
            it.remove(LAST_ERROR)
        }
    }

    suspend fun recordFailure(reason: String) {
        context.backupStore.edit { it[LAST_ERROR] = reason }
    }
}
