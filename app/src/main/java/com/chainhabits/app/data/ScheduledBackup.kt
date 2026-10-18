package com.chainhabits.app.data

import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime

/**
 * One run of the scheduled backup: the same file the "Save a backup" button writes, into
 * the folder the user chose.
 *
 * Shared by the daily worker and the "Back up now" button, so the file a user checks by
 * hand is exactly the file the schedule writes - and a failure from either is recorded in
 * the same place.
 */
class ScheduledBackup(
    private val repository: HabitRepository,
    private val settings: BackupSettings,
    private val folder: BackupFolder,
) {
    sealed interface Outcome {
        /** Written as [name], which is what the provider called it - see [BackupFolder.write]. */
        data class Saved(
            val name: String,
        ) : Outcome

        /** Switched off: no folder has been chosen. */
        data object NoFolder : Outcome

        /**
         * No habits yet. Skipped rather than written: an empty backup taken daily would push
         * the real ones out of the retention window.
         */
        data object NothingToSave : Outcome
    }

    /**
     * Writes one backup now. Throws if the folder cannot be written, after recording why
     * where the backup screen will show it.
     */
    suspend fun run(): Outcome {
        val tree = settings.currentFolder() ?: return Outcome.NoFolder
        return try {
            if (!repository.hasHabits()) return Outcome.NothingToSave
            val name =
                folder.write(
                    tree.toUri(),
                    scheduledBackupFileName(LocalDateTime.now()),
                    repository.exportBackup(Instant.now().toString()),
                )
            settings.recordSuccess(Instant.now().toString())
            Outcome.Saved(name)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // The likely causes - the folder was deleted, or the grant was revoked by
            // clearing the provider app - are permanent until someone picks a folder
            // again, and a backup that has silently stopped is worse than none at all.
            settings.recordFailure(e.message ?: "The backup folder could not be written to.")
            throw e
        }
    }
}
