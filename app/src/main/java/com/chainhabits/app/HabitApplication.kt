package com.chainhabits.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.chainhabits.app.data.BackupFolder
import com.chainhabits.app.data.BackupSettings
import com.chainhabits.app.data.HabitDatabase
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.data.ScheduledBackup
import com.chainhabits.app.notify.Reminders
import com.chainhabits.app.widget.HabitWidget

class HabitApplication : Application() {
    val repository: HabitRepository by lazy {
        // The widget lives in the launcher's process, where no Room Flow reaches it, so
        // every write has to push a refresh at it explicitly.
        HabitRepository(HabitDatabase.get(this).habitDao()) {
            HabitWidget().updateAll(this)
        }
    }

    val backupSettings: BackupSettings by lazy { BackupSettings(this) }

    val backupFolder: BackupFolder by lazy { BackupFolder(this) }

    val scheduledBackup: ScheduledBackup by lazy {
        ScheduledBackup(repository, backupSettings, backupFolder)
    }

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
    }
}
