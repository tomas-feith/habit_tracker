package com.chainhabits.app.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chainhabits.app.HabitApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The daily scheduled backup. See [com.chainhabits.app.data.ScheduledBackup] for what one
 * run does.
 *
 * WorkManager rather than an AlarmManager alarm like the reminders: a backup has no reason
 * to land at an exact minute, and WorkManager already survives reboots and app updates and
 * waits out low battery, all of which the reminders have to arrange by hand.
 *
 * No network constraint: the write is to a local content provider. Whether that provider
 * then syncs the file somewhere is the sync app's business and its own schedule.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? HabitApplication ?: return Result.failure()
        return try {
            app.scheduledBackup.run()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Already recorded for the backup screen by ScheduledBackup; this is for logcat.
            Log.w(TAG, "Scheduled backup failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "BackupWorker"

        private const val WORK_NAME = "chainhabits-scheduled-backup"

        private const val INTERVAL_HOURS = 24L

        private const val MAX_ATTEMPTS = 3

        /**
         * Register the daily backup, replacing any existing registration.
         *
         * UPDATE rather than KEEP: this is called when the user picks a folder, and that is
         * exactly the moment the schedule should restart rather than inherit the timing of
         * a registration made for a folder they have replaced.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<BackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
