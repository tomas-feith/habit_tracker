package com.chainhabits.app.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Binds [HabitWidget] to the launcher, and keeps it honest across midnight.
 *
 * A widget has no lifecycle of its own: once drawn it sits there until something pushes new
 * content at it. That is fine for data changes, which the repository's change hook covers,
 * but not for the passage of time - at midnight yesterday's ticks stop being today's, and
 * nothing writes to the database to say so. Without this the widget would keep showing
 * yesterday as done until the next tap.
 *
 * `updatePeriodMillis` could poll instead, but its floor is 30 minutes and it wakes the
 * device to do it. Listening for the clock broadcasts costs nothing and fires exactly when
 * it matters.
 */
class HabitWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = HabitWidget()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        if (intent.action !in CLOCK_ACTIONS) return

        // The broadcast returns immediately, so the update has to be kept alive explicitly
        // or the process can be killed mid-write.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                HabitWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val CLOCK_ACTIONS =
            setOf(
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )
    }
}
