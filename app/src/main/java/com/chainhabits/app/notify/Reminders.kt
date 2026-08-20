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
import com.chainhabits.app.domain.HabitEvaluator
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.ReminderDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Reminders {
    private const val CHANNEL_ID = "habit_reminders"
    const val EXTRA_HABIT_ID = "habitId"

    fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Habit reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "The daily nudge for habits you've set a reminder time on"
            }
        context
            .getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Arms [habit]'s next nudge, or cancels it if the habit can no longer earn one.
     *
     * A single one-shot alarm, re-armed by [ReminderReceiver] after every firing, rather
     * than one repeating alarm set once:
     *
     *  - `setInexactRepeating` at `INTERVAL_DAY` hands the system an alignment window hours
     *    wide, so a 07:00 reminder genuinely arrives at 09:40. `setAndAllowWhileIdle` is
     *    delivered close to its trigger and is not held back for the next Doze maintenance
     *    window. It is still not an *exact* alarm, so it needs no `SCHEDULE_EXACT_ALARM`
     *    permission, and its once-per-9-minutes budget is irrelevant to a daily reminder.
     *  - A repeating alarm advances by a fixed 24 hours, which DST and travel make wrong
     *    permanently. Re-deriving the trigger from the wall clock each time fixes both.
     *
     * It also makes cancellation self-healing: an alarm left behind by an archived, deleted
     * or restored-over habit fires once, finds nothing to say, and is not re-armed.
     */
    fun schedule(
        context: Context,
        habit: Habit,
    ) {
        val time = habit.reminderTime
        // The same conditions ReminderDecision treats as permanent. A negative habit's
        // reminder can never fire (see there), so arming it would only cost a wakeup.
        if (time == null || habit.polarity == Polarity.NEGATIVE || habit.archivedOn != null) {
            return cancel(context, habit.id)
        }

        val zone = ZoneId.systemDefault()
        val next = ReminderDecision.nextTriggerAfter(LocalDateTime.now(zone), time)
        val triggerAt = next.atZone(zone).toInstant().toEpochMilli()

        context
            .getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(context, habit.id),
            )
    }

    fun cancel(
        context: Context,
        habitId: Long,
    ) {
        context
            .getSystemService(AlarmManager::class.java)
            .cancel(pendingIntent(context, habitId))
    }

    /**
     * Re-arms every reminder from scratch.
     *
     * Alarms are boot- and install-local, and the system drops them for reasons the app
     * never hears about: a reboot, an app update, a force-stop. Cheap and idempotent, so it
     * runs on all of those and on app start rather than being reasoned about case by case.
     */
    suspend fun rescheduleAll(context: Context) {
        val app = context.applicationContext as HabitApplication
        app.repository.habitsWithReminders().forEach { schedule(context, it) }
    }

    /**
     * The alarm's handle for one habit. The same inputs must produce the same object, or
     * [cancel] cannot reach what [schedule] created.
     *
     * The request code is the *only* thing telling two habits apart here:
     * `Intent.filterEquals` - which is what the system compares - ignores extras, and
     * these intents are otherwise identical. So the narrowing of a `Long` id to an `Int`
     * request code is load-bearing, and two habits whose ids differ by exactly 2^32 would
     * share an alarm.
     *
     * Left as it is deliberately. Ids come from `AUTOINCREMENT` starting at 1, so reaching
     * that needs two billion habits; whereas the obvious fix - a per-habit `data` URI or
     * action, which `filterEquals` does compare - changes the identity of every alarm
     * already scheduled on the device. Those become unreachable by [cancel] and keep
     * firing alongside their replacements until the next reboot clears them. That is a
     * real bug traded for an unreachable one, and it would leave legacy-cancelling code
     * in the app permanently.
     */
    private fun pendingIntent(
        context: Context,
        habitId: Long,
    ): PendingIntent {
        val intent =
            Intent(context, ReminderReceiver::class.java)
                .putExtra(EXTRA_HABIT_ID, habitId)
        return PendingIntent.getBroadcast(
            context,
            habitId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun notify(
        context: Context,
        habit: Habit,
    ) {
        val open =
            PendingIntent.getActivity(
                context,
                habit.id.toInt(),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Only positive habits reach here, so there is one wording rather than one per
        // polarity - see ReminderDecision for why the negative case was dropped.
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(habit.name)
                .setContentText("Still time today.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(habit.id.toInt(), notification)
        }
    }
}

/**
 * Decides whether the nudge is still owed, then arms the next one.
 *
 * The habit and its history are re-read here rather than carried in the alarm's extras:
 * the alarm was armed a day ago, and the entire point is to judge the habit as it is at the
 * moment of interrupting the user.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val habitId = intent.getLongExtra(Reminders.EXTRA_HABIT_ID, -1L)
        if (habitId < 0) return

        val app = context.applicationContext as HabitApplication
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A deleted habit's stray alarm. Firing once and not re-arming is how it
                // gets cleaned up.
                val habit = app.repository.getHabit(habitId) ?: return@launch
                val today = LocalDate.now()

                val owed =
                    ReminderDecision.shouldNotify(
                        habit = habit,
                        // The week containing today is the widest window any cadence
                        // judges, so it covers all of them.
                        entries =
                            app.repository.entriesFor(
                                habitId,
                                HabitEvaluator.weekStart(today),
                            ),
                        pauses = app.repository.pausesFor(habitId),
                        today = today,
                    )
                if (owed) Reminders.notify(context, habit)

                // Unconditional: a nudge not owed today says nothing about tomorrow.
                Reminders.schedule(context, habit)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Puts the alarms back whenever the system has dropped them or the clock has moved.
 *
 * Alarms survive neither a reboot nor an app update, and one anchored to an absolute
 * instant is wrong after a DST change or a flight. All four are the same fix, so they share
 * a receiver.
 */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in HANDLED) return

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

    private companion object {
        val HANDLED =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )
    }
}
