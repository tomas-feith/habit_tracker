package com.chainhabits.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import com.chainhabits.app.HabitApplication
import java.time.LocalDate

private val HABIT_ID = ActionParameters.Key<Long>("habitId")

internal fun logHabitParameters(habitId: Long): ActionParameters =
    actionParametersOf(HABIT_ID to habitId)

/**
 * Logs today's event for a habit tapped in the widget.
 *
 * Runs in the app's process, not the launcher's - the launcher only sends the callback. The
 * repository's change hook refreshes every widget afterwards, so no explicit update is
 * needed here.
 */
class LogHabitAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val habitId = parameters[HABIT_ID] ?: return
        val repository = (context.applicationContext as HabitApplication).repository

        // Re-read rather than trusting the tapped row: the widget's copy can be stale by
        // the time the tap lands, and logging against a deleted habit would insert an
        // orphan entry.
        val habit = repository.getHabit(habitId) ?: return
        repository.logEvent(habit, LocalDate.now())
    }
}
