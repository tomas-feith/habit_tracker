package com.tsfeith.habits

import android.app.Application
import com.tsfeith.habits.data.HabitDatabase
import com.tsfeith.habits.data.HabitRepository
import com.tsfeith.habits.notify.Reminders

class HabitApplication : Application() {
    val repository: HabitRepository by lazy {
        HabitRepository(HabitDatabase.get(this).habitDao())
    }

    override fun onCreate() {
        super.onCreate()
        Reminders.createChannel(this)
    }
}
