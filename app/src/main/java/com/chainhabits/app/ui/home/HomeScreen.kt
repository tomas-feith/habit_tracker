package com.chainhabits.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import com.chainhabits.app.ui.components.MosaicStrip
import com.chainhabits.app.ui.components.QuotaPips
import com.chainhabits.app.ui.theme.MosaicTheme
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.text.intl.Locale as ComposeLocale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddHabit: () -> Unit,
    onOpenHabit: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Compose's locale rather than Locale.getDefault(), so the date re-formats if the
    // user changes language while the app is running.
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }

    // Without this the app keeps yesterday's date after being left open overnight, and
    // taps would be logged against the wrong day.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshDate()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = {
                    Text(
                        text = state.today.format(dateFormat),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHabit) {
                Icon(Icons.Default.Add, contentDescription = "Add habit")
            }
        },
    ) { padding ->
        if (state.loaded && state.rows.isEmpty()) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val atRisk = state.atRisk
            if (atRisk.isNotEmpty()) {
                item(key = "at-risk") { NeverMissTwiceBanner(atRisk) }
            }

            items(state.rows, key = { it.habit.id }) { row ->
                HabitCard(
                    row = row,
                    onLog = { viewModel.logEvent(row.habit) },
                    onUndo = { viewModel.removeEvent(row.habit) },
                    onOpen = { onOpenHabit(row.habit.id) },
                )
            }
        }
    }
}

/**
 * The one nudge the app makes on its own.
 *
 * Shown only for standard habits sitting on exactly one miss - the moment where, per
 * Clear, the habit is actually in danger. A strict habit never appears here because its
 * chain has already broken rather than being at risk.
 */
@Composable
private fun NeverMissTwiceBanner(atRisk: List<HabitRowState>) {
    // Tinted from the mosaic's own "missed once" amber rather than a Material error
    // colour. A red banner would say "you failed", which is the opposite of the point:
    // one miss is recoverable, and the banner is meant to be a nudge, not an alarm.
    val amber = MosaicTheme.colors.missedOnce
    Card(
        colors = CardDefaults.cardColors(containerColor = amber.copy(alpha = 0.22f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = amber,
            )
            Column {
                Text(
                    text = "Never miss twice",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text =
                        atRisk.joinToString(", ") { it.habit.name } +
                            if (atRisk.size == 1) {
                                " slipped once. The chain is still intact."
                            } else {
                                " slipped once. The chains are still intact."
                            },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun HabitCard(
    row: HabitRowState,
    onLog: () -> Unit,
    onUndo: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.habit.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = headline(row),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                LogControl(row, onLog, onUndo)
            }

            Spacer(Modifier.height(10.dp))
            MosaicStrip(cells = row.cells)

            row.weeklyTarget?.let { target ->
                if (row.habit.showsQuotaPips) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuotaPips(done = row.currentCount, target = target)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = quotaLabel(row, target),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The control that logs an event.
 *
 * A positive habit gets a checkbox-style tap. A negative habit gets an explicit
 * "I slipped" button rather than a pre-checked box: tapping a checked box to record a
 * failure is backwards, and far too easy to hit by accident.
 */
@Composable
private fun LogControl(
    row: HabitRowState,
    onLog: () -> Unit,
    onUndo: () -> Unit,
) {
    val habit = row.habit
    val colors = MosaicTheme.colors

    when {
        habit.polarity == Polarity.NEGATIVE -> {
            OutlinedButton(onClick = onLog) {
                Text("I slipped")
            }
        }

        habit.cadence is Cadence.TimesPerWeek -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Weekly habits accumulate rather than toggle, so undo has to be explicit.
                if (row.currentCount > 0) {
                    OutlinedButton(onClick = onUndo, contentPadding = PaddingValues(8.dp, 4.dp)) {
                        Text("-")
                    }
                }
                OutlinedButton(onClick = onLog) { Text("Log one") }
            }
        }

        else -> {
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (row.isSatisfied) {
                                colors.done
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ).clickable(onClick = onLog),
                contentAlignment = Alignment.Center,
            ) {
                if (row.isSatisfied) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done today",
                        tint = MaterialTheme.colorScheme.surface,
                    )
                }
            }
        }
    }
}

private fun headline(row: HabitRowState): String {
    val unit = if (row.habit.isWeekly) "week" else "day"
    val n = row.headlineCount
    val plural = if (n == 1) unit else "${unit}s"

    return when {
        row.habit.strictness == Strictness.STRICT && row.habit.polarity == Polarity.NEGATIVE -> {
            if (n == 0) "Restarting today" else "$n $plural clean"
        }

        n == 0 -> {
            "Starting over"
        }

        row.stats.atRisk -> {
            "$n $plural, one miss - don't miss twice"
        }

        else -> {
            "$n $plural"
        }
    }
}

private fun quotaLabel(
    row: HabitRowState,
    target: Int,
): String =
    when {
        row.habit.polarity == Polarity.NEGATIVE -> {
            "${row.currentCount} of $target allowance used this week"
        }

        row.currentCount > target -> {
            "${row.currentCount} of $target this week - over target"
        }

        else -> {
            "${row.currentCount} of $target this week"
        }
    }

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No habits yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Start with one. Make it small enough that you can't talk yourself out of it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
