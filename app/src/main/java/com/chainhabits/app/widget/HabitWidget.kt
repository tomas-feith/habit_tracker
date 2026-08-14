package com.chainhabits.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.chainhabits.app.HabitApplication
import com.chainhabits.app.MainActivity
import com.chainhabits.app.domain.CellState
import java.time.LocalDate

/**
 * The home-screen widget: today's habits, tappable.
 *
 * The point of the app is to put the decision in front of you at the moment you make it,
 * and a launcher screen is closer to that moment than anything inside the app. A positive
 * habit can be ticked without opening anything.
 *
 * Glance renders to `RemoteViews` running in the launcher's process, so none of the app's
 * own Compose UI is reusable here and no Room `Flow` reaches it. The widget is refreshed by
 * being pushed at - see [HabitWidgetReceiver] for clock changes, and
 * `HabitRepository.onDataChanged` for writes made inside the app.
 */
class HabitWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val repository = (context.applicationContext as HabitApplication).repository

        provideContent {
            // Read inside the composition so today is re-derived whenever the widget is
            // recomposed, rather than being captured once when the session started.
            val today = LocalDate.now()
            val rows by repository.observeWidgetRows(today).collectAsState(initial = null)
            GlanceTheme {
                WidgetBody(rows)
            }
        }
    }

    @Composable
    private fun WidgetBody(rows: List<WidgetRow>?) {
        Column(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .background(WidgetColors.surface)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Header(rows)
            Spacer(GlanceModifier.height(10.dp))

            // Null means the first query has not returned yet, and is deliberately drawn as
            // nothing: showing "no habits yet" during that gap would flash a wrong empty
            // state on every refresh.
            if (rows == null) return@Column

            if (rows.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn {
                    items(rows, itemId = { it.id }) { row -> HabitRow(row) }
                }
            }
        }
    }

    @Composable
    private fun Header(rows: List<WidgetRow>?) {
        val done = rows?.count { it.isSatisfied } ?: 0
        val total = rows?.size ?: 0
        Row(
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Today",
                style =
                    TextStyle(
                        color = WidgetColors.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (total > 0) {
                Text(
                    text = "$done/$total",
                    style = TextStyle(color = WidgetColors.inkSoft, fontSize = 13.sp),
                )
            }
        }
    }

    @Composable
    private fun EmptyState() {
        Text(
            text = "No habits yet. Tap to add one.",
            style = TextStyle(color = WidgetColors.inkSoft, fontSize = 13.sp),
            modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
        )
    }

    @Composable
    private fun HabitRow(row: WidgetRow) {
        Row(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .semantics { contentDescription = describe(row) }
                    .clickable(rowAction(row)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusTile(row)
            Spacer(GlanceModifier.width(10.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = row.name,
                    maxLines = 1,
                    style =
                        TextStyle(
                            color = WidgetColors.ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                )
                Text(
                    text = row.subtitle,
                    maxLines = 1,
                    style = TextStyle(color = WidgetColors.inkSoft, fontSize = 11.sp),
                )
            }
        }
    }

    /**
     * A tap logs the event for a positive habit and opens the app for a negative one.
     *
     * See [WidgetRow.isTappable] for why negative habits are read-only here.
     */
    private fun rowAction(row: WidgetRow) =
        if (row.isTappable) {
            actionRunCallback<LogHabitAction>(logHabitParameters(row.id))
        } else {
            actionStartActivity<MainActivity>()
        }

    /**
     * The square standing in for the habit's cell in the mosaic.
     *
     * It carries the same meaning as on the home screen - solid is a good period, amber is
     * one recoverable miss, clay is a broken chain - so the widget reads as a slice of the
     * app rather than a second, competing visual language.
     */
    @Composable
    private fun StatusTile(row: WidgetRow) {
        Box(
            modifier =
                GlanceModifier
                    .size(26.dp)
                    .cornerRadius(8.dp)
                    .background(if (row.isFilled) tileColor(row) else WidgetColors.tileEmpty),
            contentAlignment = Alignment.Center,
        ) {
            if (row.showsPartialCount) {
                Text(
                    text = row.count.toString(),
                    style =
                        TextStyle(
                            color = WidgetColors.inkSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            } else if (row.isFilled) {
                Text(
                    text = "✓",
                    style =
                        TextStyle(
                            color = ColorProvider(Color.White, Color.Black),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                )
            }
        }
    }

    private fun tileColor(row: WidgetRow) =
        when (row.state) {
            CellState.BROKEN -> WidgetColors.broken
            CellState.MISSED_ONCE -> WidgetColors.missedOnce
            else -> WidgetColors.done
        }

    /** TalkBack reads the whole row, since the tile alone is meaningless out loud. */
    private fun describe(row: WidgetRow): String {
        val action = if (row.isTappable) "Tap to log." else "Tap to open."
        val status = if (row.isSatisfied) "done" else "not done"
        return "${row.name}, $status, ${row.subtitle}. $action"
    }
}
