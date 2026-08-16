package com.chainhabits.app.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chainhabits.app.domain.Backfill
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.CellState
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.HabitEvaluator
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import com.chainhabits.app.ui.components.MosaicLegend
import com.chainhabits.app.ui.components.MosaicStrip
import com.chainhabits.app.ui.components.YearMosaic
import com.chainhabits.app.ui.theme.MosaicTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.text.intl.Locale as ComposeLocale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val habit = state.habit
    val stats = state.stats

    LifecycleResumeEffect(Unit) {
        viewModel.refreshDate()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(habit?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Pausing lives here rather than on the home row: the home row's
                    // long-press is already the drag handle, and suspending a habit is a
                    // deliberate act that deserves a deliberate place.
                    IconButton(onClick = { viewModel.setPaused(!state.isPaused) }) {
                        Icon(
                            if (state.isPaused) {
                                Icons.Default.PlayCircleOutline
                            } else {
                                Icons.Default.PauseCircleOutline
                            },
                            contentDescription =
                                if (state.isPaused) "Resume habit" else "Pause habit",
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit habit")
                    }
                },
            )
        },
    ) { padding ->
        if (habit == null || stats == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Headline(habit, stats.currentStreak, stats.chainLength)

            // Directly under the headline: the note is the "why", and the why is worth
            // reading before the numbers, not after them.
            habit.note?.let { NoteCard(it) }

            Section("Recent") {
                MosaicStrip(cells = state.periodCells, cellHeight = 18.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = cadenceDescription(habit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("The last year") {
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    YearMosaic(
                        cells = state.yearCells,
                        modifier = Modifier.width(720.dp),
                        onDayClick = { viewModel.selectDay(it.date) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                MosaicLegend(strict = habit.strictness == Strictness.STRICT)
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        "Tap a day to see it. The last ${Backfill.WINDOW_DAYS} days can " +
                            "still be corrected if you forgot to log one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The heatmap's own cells are 12dp Canvas squares: too small to be a fair
                // touch target and invisible to a screen reader. This button is the same
                // correction as a real, labelled control, so the feature does not depend
                // on being able to hit one of 363 squares.
                if (state.backfillDays.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::openBackfill) {
                        Text("Fix a missed day")
                    }
                }
            }

            Section("Numbers") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Longest", "${stats.longestStreak}", Modifier.weight(1f))
                    Stat(
                        "Completion",
                        "${(stats.completionRate * 100).roundToInt()}%",
                        Modifier.weight(1f),
                    )
                    Stat("Current", "${stats.currentStreak}", Modifier.weight(1f))
                }
            }

            if (state.months.isNotEmpty()) {
                Section("By month") { MonthChart(state.months) }
            }
        }

        state.selectedDay?.let { day ->
            DaySheet(
                habit = habit,
                day = day,
                onSetCount = { count -> viewModel.setDayCount(day.date, count) },
                onDismiss = viewModel::dismissDay,
            )
        }

        if (state.backfillOpen) {
            BackfillSheet(
                habit = habit,
                days = state.backfillDays,
                today = state.today,
                onSetCount = viewModel::setDayCount,
                onDismiss = viewModel::dismissBackfill,
            )
        }
    }
}

/**
 * The correction window as a list, one real control per day.
 *
 * This is the accessible route to the same edit the heatmap offers by tap. It is a flat
 * list of at most [Backfill.WINDOW_DAYS] rows rather than a grid, because that is what a
 * screen reader can actually navigate - swiping through 363 undifferentiated cells to
 * find last Tuesday is not a feature anyone would use. Each row is a single focusable
 * node that announces the date, what is recorded, and what toggling it will do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackfillSheet(
    habit: Habit,
    days: List<DaySelection>,
    today: LocalDate,
    onSetCount: (LocalDate, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp, 0.dp, 24.dp, 32.dp),
        ) {
            Text("Fix a missed day", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Did it but forgot to log it? Set the day straight here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            BackfillDays(habit, days, today, onSetCount)
        }
    }
}

/**
 * The rows themselves, separate from the sheet that hosts them.
 *
 * Split out to be testable: their semantics are the whole point of this control, and a
 * `ModalBottomSheet` is an awkward thing to stand up in a test for no benefit. See
 * `BackfillSheetTest`, which exists because a plausible-looking fix here silently did
 * nothing.
 */
@Composable
internal fun BackfillDays(
    habit: Habit,
    days: List<DaySelection>,
    today: LocalDate,
    onSetCount: (LocalDate, Int) -> Unit,
) {
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    val format = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }

    days.forEach { day ->
        val label = dayLabel(day.date, today, format)
        if (habit.cadence is Cadence.TimesPerWeek) {
            CountRow(label, dayStatus(habit, day), day, onSetCount)
        } else {
            CheckRow(label, dayStatus(habit, day), day, onSetCount)
        }
    }
}

/**
 * A day that is simply done or not.
 *
 * The whole row is the toggle rather than the checkbox alone: it gives a target far above
 * the 48dp minimum, and it collapses to one node that announces as a checkbox with its
 * date and state, instead of a stray tick TalkBack would read with no idea what it is for.
 */
@Composable
private fun CheckRow(
    label: String,
    status: String,
    day: DaySelection,
    onSetCount: (LocalDate, Int) -> Unit,
) {
    val done = day.count > 0
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(
                    value = done,
                    role = Role.Checkbox,
                    onValueChange = { on -> onSetCount(day.date, if (on) 1 else 0) },
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Null callback: the row above owns the input, so this draws state only and does
        // not become a second thing to focus.
        Checkbox(checked = done, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A day that counts events, for a times-per-week habit. */
@Composable
private fun CountRow(
    label: String,
    status: String,
    day: DaySelection,
    onSetCount: (LocalDate, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One labelled node for the date and what is recorded. mergeDescendants alone was
        // not enough here - it left an unlabelled, non-focusable parent with the two texts
        // still exposed beneath it - so the label is set outright and the children are
        // cleared. Without this a screen reader walks five nodes per day where the
        // checkbox rows take one.
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = "$label. $status" },
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Named per day, not "add" and "remove": a screen reader reaches these one after
        // another down the list, where a bare "add" gives no clue which day it lands on.
        IconButton(
            onClick = { onSetCount(day.date, day.count - 1) },
            enabled = day.count > 0,
        ) {
            Icon(Icons.Default.Remove, contentDescription = "One fewer on $label")
        }
        // Hidden from accessibility rather than labelled: the status line above already
        // says the count in words, so exposing the bare numeral adds a stop that reads
        // out "2" with nothing to attach it to.
        Text(
            text = "${day.count}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.clearAndSetSemantics {},
        )
        IconButton(onClick = { onSetCount(day.date, day.count + 1) }) {
            Icon(Icons.Default.Add, contentDescription = "One more on $label")
        }
    }
}

/** "Today" and "Yesterday" beat a date for the two days this is mostly used on. */
private fun dayLabel(
    date: LocalDate,
    today: LocalDate,
    format: DateTimeFormatter,
): String =
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(format)
    }

/**
 * The correction sheet for one day of the heatmap.
 *
 * Every tapped day opens it, not just the editable ones: "what was the 3rd?" is a fair
 * question about any square, and a grid where most taps do nothing at all reads as broken.
 * Outside the window it simply says so instead of offering buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(
    habit: Habit,
    day: DaySelection,
    onSetCount: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Compose's locale rather than Locale.getDefault(), so the date re-formats if the user
    // changes language while the app is running. Carries the year: every square in the
    // heatmap opens this sheet, and the grid reaches back far enough that a bare
    // "Monday 3 November" would be genuinely ambiguous.
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    val format =
        remember(locale) {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp, 0.dp, 24.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(day.date.format(format), style = MaterialTheme.typography.titleLarge)
            Text(
                text = dayStatus(habit, day),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            if (!day.editable) {
                Text(
                    text = settledReason(habit, day),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            if (habit.cadence is Cadence.TimesPerWeek) {
                // Weekly habits count events, so the correction is a number, not a flag -
                // two forgotten workouts on one Saturday is a real thing to record.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { onSetCount(day.count - 1) },
                        enabled = day.count > 0,
                    ) { Text("-") }
                    Text(
                        text = "${day.count}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = { onSetCount(day.count + 1) }) { Text("+") }
                }
            } else if (day.count > 0) {
                OutlinedButton(onClick = { onSetCount(0) }) { Text("Clear this day") }
            } else {
                Button(onClick = { onSetCount(1) }) {
                    Text(if (habit.polarity == Polarity.NEGATIVE) "Mark a slip" else "Mark done")
                }
            }
        }
    }
}

/** What the day currently holds, in the mosaic's own language. */
private fun dayStatus(
    habit: Habit,
    day: DaySelection,
): String {
    val slip = habit.polarity == Polarity.NEGATIVE
    if (day.count > 1) return "${day.count} logged"
    if (day.count == 1) return if (slip) "Slipped" else "Done"

    return when (day.state) {
        CellState.DONE -> if (slip) "Clean" else "Done"
        CellState.MISSED_ONCE -> if (slip) "Slipped" else "Missed - the chain survived it"
        CellState.BROKEN -> if (slip) "Slipped - chain broken" else "Missed - chain broken"
        CellState.PENDING -> if (slip) "Clean so far" else "Not logged yet"
        CellState.NOT_SCHEDULED -> "Not scheduled"
    }
}

/**
 * Why a day offers no buttons.
 *
 * Four different reasons, and collapsing them would misinform: "not due on a Tuesday" and
 * "too old to change" are nothing alike, and telling someone a habit "wasn't running" on a
 * day it simply wasn't scheduled for is just wrong.
 */
private fun settledReason(
    habit: Habit,
    day: DaySelection,
): String =
    when {
        day.date < habit.createdOn -> {
            "This habit didn't exist yet."
        }

        habit.archivedOn?.let { day.date >= it } == true -> {
            "This habit was archived by then."
        }

        !HabitEvaluator.isScheduled(habit, day.date) -> {
            "Not due on this day."
        }

        else -> {
            "Settled. Only the last ${Backfill.WINDOW_DAYS} days can be corrected - " +
                "past that, the record stands."
        }
    }

/**
 * The number the screen leads with.
 *
 * Strict habits lead with the honest streak - "days since" is the entire point of that
 * mode. Standard habits lead with the chain, which survives an isolated miss, so a single
 * sick day doesn't present as total failure.
 */
@Composable
private fun Headline(
    habit: Habit,
    currentStreak: Int,
    chainLength: Int,
) {
    val strict = habit.strictness == Strictness.STRICT
    val value = if (strict) currentStreak else chainLength
    val unit = if (habit.isWeekly) "week" else "day"

    Column {
        Text(
            text = "$value",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = MosaicTheme.colors.done,
        )
        Text(
            text =
                when {
                    strict && habit.polarity == Polarity.NEGATIVE -> {
                        "${unit}s clean" + if (value == 0) " - restarting today" else ""
                    }

                    strict -> {
                        "${unit}s in a row"
                    }

                    else -> {
                        "${unit}s in the current chain"
                    }
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!strict && currentStreak != chainLength) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$currentStreak without a miss. The chain survived one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoteCard(note: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Column { content() }
    }
}

@Composable
private fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthChart(months: List<MonthBar>) {
    val colors = MosaicTheme.colors
    Column {
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val gap = 10f
            val barWidth = (size.width - gap * (months.size - 1)) / months.size
            months.forEachIndexed { i, m ->
                val h = size.height * m.rate.coerceIn(0f, 1f)
                val x = i * (barWidth + gap)
                // Track behind each bar, so an empty month still reads as a slot.
                drawRect(
                    color = colors.notScheduled,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                )
                drawRect(
                    color = colors.done,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            months.forEach {
                Text(
                    text = it.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun cadenceDescription(habit: Habit): String {
    val strictness =
        if (habit.strictness == Strictness.STRICT) {
            "One miss breaks the chain."
        } else {
            "One miss is recoverable; two in a row breaks the chain."
        }
    val cadence =
        when (val c = habit.cadence) {
            is Cadence.Daily -> {
                "Every day."
            }

            is Cadence.SpecificDays -> {
                c.days
                    .sortedBy { it.value }
                    .joinToString(
                        ", ",
                    ) { d ->
                        d.name
                            .take(3)
                            .lowercase()
                            .replaceFirstChar { it.uppercase() }
                    }.let { "On $it." }
            }

            is Cadence.TimesPerWeek -> {
                if (habit.polarity == Polarity.NEGATIVE) {
                    "At most ${c.target}x per week. One cell is one week."
                } else {
                    "At least ${c.target}x per week. One cell is one week."
                }
            }
        }
    return "$cadence $strictness"
}
