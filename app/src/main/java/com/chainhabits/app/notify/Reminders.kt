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
import androidx.core.net.toUri
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
import java.time.LocalTime
import java.time.ZoneId

object Reminders {
    private const val CHANNEL_ID = "habit_reminders"
    const val EXTRA_HABIT_ID = "habitId"

    /** Scheme for the per-habit URIs that give alarms and notifications their identity. */
    private const val SCHEME = "chainhabits"

    /**
     * Shared by every reminder PendingIntent, because the `data` URI already distinguishes
     * them and a second discriminator would only be a second thing to keep in sync.
     */
    private const val REQUEST_CODE = 0

    /** Reminders are told apart by their tag, so they can all share one id. */
    private const val NOTIFICATION_ID = 0

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
     * Arms the next nudge for every habit that can still earn one.
     *
     * The only arming path in the app, which is what lets it stagger: habits sharing a
     * reminder time have to be spread past the idle throttle (see
     * [ReminderDecision.staggeredTime]), and that decision cannot be made one habit at a
     * time. Cheap - one query - and idempotent, since re-arming an alarm replaces it with
     * itself, so every caller that might have invalidated the schedule just calls this.
     *
     * A one-shot alarm re-armed after each firing, rather than one repeating alarm set once:
     *
     *  - `setInexactRepeating` at `INTERVAL_DAY` hands the system an alignment window hours
     *    wide, so a 07:00 reminder genuinely arrives at 09:40. `setAndAllowWhileIdle` is
     *    delivered close to its trigger and is not held back for the next Doze maintenance
     *    window. It is still not an *exact* alarm, so it needs no `SCHEDULE_EXACT_ALARM`
     *    permission.
     *  - A repeating alarm advances by a fixed 24 hours, which DST and travel make wrong
     *    permanently. Re-deriving the trigger from the wall clock each time fixes both.
     *
     * It also makes cancellation self-healing: an alarm left behind by an archived, deleted
     * or restored-over habit fires once, finds nothing to arm, and is gone.
     */
    suspend fun rescheduleAll(context: Context) = reschedule(context, only = null)

    /**
     * Arms the next nudge for [habitId] alone, leaving every other alarm untouched.
     *
     * What a firing reminder must use, and the reason [reschedule] can narrow at all.
     * Re-arming everything from inside a firing is actively destructive: alarms are
     * delivered with seconds of jitter, so a 07:00 alarm can arrive at 07:01, and the
     * re-arm it triggers would compute "07:01 is past 07:01, go to tomorrow" for the habit
     * whose own alarm is at 07:01 and still queued - replacing that pending alarm with
     * tomorrow's and dropping today's nudge silently. Measured on a device: six alarms due,
     * five delivered.
     *
     * The habit that just fired is the one case where rolling forward is unambiguously
     * right, because its alarm has demonstrably already gone off.
     */
    suspend fun rescheduleOne(
        context: Context,
        habitId: Long,
    ) = reschedule(context, only = habitId)

    private suspend fun reschedule(
        context: Context,
        only: Long?,
    ) {
        val app = context.applicationContext as HabitApplication
        app.repository
            .habitsWithReminders()
            // A negative habit's reminder can never fire (see [ReminderDecision]), so
            // arming it would only buy a wakeup and a slot in someone else's stagger.
            .filter { it.polarity != Polarity.NEGATIVE }
            .mapNotNull { habit -> habit.reminderTime?.let { habit to it } }
            .groupBy({ it.second }, { it.first })
            .forEach { (time, sharing) ->
                // Ordered by id so the stagger is stable: a habit keeps its slot across
                // runs rather than shuffling with whatever order the query returned. The
                // whole group is walked even when only one habit is being armed, because a
                // habit's slot is a property of the group, not of the habit.
                sharing.sortedBy { it.id }.forEachIndexed { slot, habit ->
                    if (only == null || habit.id == only) {
                        arm(context, habit.id, ReminderDecision.staggeredTime(time, slot))
                    }
                }
            }
    }

    private fun arm(
        context: Context,
        habitId: Long,
        time: LocalTime,
    ) {
        val zone = ZoneId.systemDefault()
        val next = ReminderDecision.nextTriggerAfter(LocalDateTime.now(zone), time)

        context
            .getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next.atZone(zone).toInstant().toEpochMilli(),
                pendingIntent(context, habitId),
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
     * The alarm's handle for one habit. The same inputs must produce the same object, or
     * [cancel] cannot reach what [rescheduleAll] created.
     *
     * The per-habit `data` URI is what tells two habits apart. `Intent.filterEquals` - which
     * is what the system compares - ignores extras, so the extra alone would make every
     * reminder the same alarm; a `Long` id narrowed into the `Int` request code would work
     * for any realistic id but is a silent collision at 2^32.
     *
     * The URI was previously avoided because changing an alarm's identity strands the ones
     * already scheduled on the device, unreachable by [cancel] and firing beside their
     * replacements. For anything this code arms that is now harmless - a stranded one-shot
     * alarm fires once, is judged by [ReminderDecision] like any other firing, and is gone.
     *
     * It was *not* harmless for the alarms left by the versionCode 7 build, which were
     * repeating: those keep firing daily, at a drifting time inside an 18-hour window, and
     * nothing this app can do reaches them. Upgrading in place therefore leaves duplicates
     * until the alarms are dropped by a reboot or a force-stop. Observed on device after
     * the 7 -> 8 update, and cleared with a force-stop; noted here because the same trap
     * waits for any future change to this method.
     */
    private fun pendingIntent(
        context: Context,
        habitId: Long,
    ): PendingIntent {
        val intent =
            Intent(context, ReminderReceiver::class.java)
                .setData("$SCHEME://reminder/$habitId".toUri())
                .putExtra(EXTRA_HABIT_ID, habitId)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
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
                REQUEST_CODE,
                Intent(context, MainActivity::class.java)
                    .setData("$SCHEME://habit/${habit.id}".toUri()),
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

        // Tagged with the id as a string rather than keyed by it as an Int: the narrowing
        // would silently collapse two habits' notifications into one at 2^32, and a String
        // tag costs nothing to avoid it.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat
                .from(context)
                .notify(habit.id.toString(), NOTIFICATION_ID, notification)
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
                // Before anything else, and for this habit only. Everything below can be
                // lost if the system tears the process down mid-broadcast, and losing a
                // notification costs one nudge, whereas losing the re-arm would end the
                // chain of alarms and the habit would go silent until the next midnight.
                Reminders.rescheduleOne(context, habitId)

                // A deleted habit's stray alarm: rescheduleOne found nothing to arm, so it
                // has already gone for good.
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
 * instant is wrong after a DST change or a flight. All of it is the same fix, so it shares
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
                // A daily safety net rather than a clock event: the schedule is a chain of
                // one-shot alarms, each armed by the last, and a process killed at the
                // wrong moment breaks it silently. Midnight rebuilds it either way.
                Intent.ACTION_DATE_CHANGED,
            )
    }
}
