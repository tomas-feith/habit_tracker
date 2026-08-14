package com.chainhabits.app

import android.app.Application
import com.chainhabits.app.data.HabitDatabase
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.notify.Reminders

class HabitApplication : Application() {

    val repository: HabitRepository by lazy {
        HabitRepository(HabitDatabase.get(this).habitDao())
    }

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
    }
}
