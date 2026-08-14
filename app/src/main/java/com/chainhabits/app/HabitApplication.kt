package com.chainhabits.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.chainhabits.app.data.HabitDatabase
import com.chainhabits.app.data.HabitRepository
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

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
    }
}
