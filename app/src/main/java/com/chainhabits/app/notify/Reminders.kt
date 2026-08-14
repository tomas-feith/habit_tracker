package com.chainhabits.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chainhabits.app.HabitApplication
import com.chainhabits.app.MainActivity
import com.chainhabits.app.R
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

object Reminders {

    private const val CHANNEL_ID = "habit_reminders"
    const val EXTRA_HABIT_ID = "habitId"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Habit reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "The daily nudge for habits you've set a reminder time on"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Schedules [habit]'s daily nudge.
     *
     * Deliberately an inexact repeating alarm: a habit reminder is worth nothing extra at
     * exactly 07:00 versus 07:12, and inexact alarms avoid asking for the exact-alarm
     * permission and are far kinder to the battery.
     */
    fun schedule(context: Context, habit: Habit) {
        val time = habit.reminderTime ?: return cancel(context, habit.id)
        val alarms = context.getSystemService(AlarmManager::class.java)

        var next = LocalDateTime.of(java.time.LocalDate.now(), time)
        if (next.isBefore(LocalDateTime.now())) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        alarms.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context, habit.id),
        )
    }

    fun cancel(context: Context, habitId: Long) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, habitId))
    }

    suspend fun rescheduleAll(context: Context) {
        val app = context.applicationContext as HabitApplication
        app.repository.habitsWithReminders().forEach { schedule(context, it) }
    }

    private fun pendingIntent(context: Context, habitId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_HABIT_ID, habitId)
        return PendingIntent.getBroadcast(
            context,
            habitId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notify(context: Context, habit: Habit) {
        val open = PendingIntent.getActivity(
            context,
            habit.id.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = when (habit.polarity) {
            Polarity.POSITIVE -> "Still time today."
            Polarity.NEGATIVE -> "Staying clean? Log a slip only if you had one."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(habit.name)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(habit.id.toInt(), notification)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getLongExtra(Reminders.EXTRA_HABIT_ID, -1L)
        if (habitId < 0) return

        val app = context.applicationContext as HabitApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.getHabit(habitId)?.let { Reminders.notify(context, it) }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms don't survive a reboot, so put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // goAsync keeps the process alive past onReceive; without it the reschedule
        // races the system tearing the process down and the alarms stay lost.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Reminders.rescheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
