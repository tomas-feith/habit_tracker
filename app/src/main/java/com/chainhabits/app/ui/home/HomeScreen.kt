package com.chainhabits.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import com.chainhabits.app.ui.components.MosaicStrip
import com.chainhabits.app.ui.components.QuotaPips
import com.chainhabits.app.ui.components.rememberReorderState
import com.chainhabits.app.ui.components.reorderableItem
import com.chainhabits.app.ui.theme.MosaicTheme
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.text.intl.Locale as ComposeLocale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddHabit: () -> Unit,
    onOpenHabit: (Long) -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Compose's locale rather than Locale.getDefault(), so the date re-formats if the
    // user changes language while the app is running.
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }

    val listState = rememberLazyListState()
    val reorder =
        rememberReorderState(
            listState = listState,
            onMove = { from, to -> viewModel.moveHabit(from, to) },
            // Persist when the drag ends rather than on every swap mid-gesture.
            onDrop = { viewModel.commitOrder() },
        )

    // Without this the app keeps yesterday's date after being left open overnight, and
    // taps would be logged against the wrong day.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshDate()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text("Today", style = MaterialTheme.typography.headlineLarge)
                },
                actions = {
                    IconButton(onClick = onOpenBackup) {
                        Icon(Icons.Default.Settings, contentDescription = "Backup")
                    }
                    if (state.rows.isNotEmpty()) {
                        // The holiday switch. Pausing everything one habit at a time is
                        // exactly the friction that makes people skip it and eat the misses.
                        IconButton(onClick = { viewModel.setAllPaused(!state.allPaused) }) {
                            Icon(
                                if (state.allPaused) {
                                    Icons.Default.PlayCircleOutline
                                } else {
                                    Icons.Default.PauseCircleOutline
                                },
                                contentDescription =
                                    if (state.allPaused) {
                                        "Resume all habits"
                                    } else {
                                        "Pause all habits"
                                    },
                            )
                        }
                    }
                    Text(
                        text = state.today.format(dateFormat),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 20.dp),
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddHabit,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Habit") },
            )
        },
    ) { padding ->
        if (state.loaded && state.rows.isEmpty()) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val atRisk = state.atRisk
            if (atRisk.isNotEmpty()) {
                item(key = "at-risk") { NeverMissTwiceBanner(atRisk) }
            }

            items(state.rows, key = { it.habit.id }) { row ->
                HabitCard(
                    row = row,
                    dragging = reorder.draggingKey == row.habit.id,
                    onLog = { viewModel.logEvent(row.habit) },
                    onUndo = { viewModel.removeEvent(row.habit) },
                    onOpen = { onOpenHabit(row.habit.id) },
                    modifier = Modifier.reorderableItem(reorder, row.habit.id),
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
    val colors = MosaicTheme.colors
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.warningSurface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = colors.missedOnce)
            Column {
                Text(
                    text = "Never miss twice",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text =
                        atRisk.joinToString(", ") { it.habit.name } +
                            if (atRisk.size == 1) {
                                " slipped once. The chain is still intact."
                            } else {
                                " slipped once. The chains are still intact."
                            },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HabitCard(
    row: HabitRowState,
    dragging: Boolean,
    onLog: () -> Unit,
    onUndo: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lift by animateFloatAsState(if (dragging) 1.02f else 1f, label = "lift")

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .scale(lift)
                .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (dragging) 8.dp else 1.dp,
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.habit.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    HeroCount(row)
                }
                Spacer(Modifier.width(12.dp))
                LogControl(row, onLog, onUndo)
            }

            Spacer(Modifier.height(12.dp))
            MosaicStrip(cells = row.cells)

            row.weeklyTarget?.let { target ->
                if (row.habit.showsQuotaPips) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuotaPips(done = row.currentCount, target = target)
                        Spacer(Modifier.width(10.dp))
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
 * The streak, as the loudest thing on the row.
 *
 * This number is the entire reward the app offers - the payoff for the habit itself is
 * months away - so it gets display type in the mosaic's own green rather than being
 * tucked into a grey caption.
 */
@Composable
private fun HeroCount(row: HabitRowState) {
    val colors = MosaicTheme.colors
    val starting = row.headlineCount == 0

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "${row.headlineCount}",
            style = MaterialTheme.typography.displaySmall,
            color = if (starting) MaterialTheme.colorScheme.onSurfaceVariant else colors.done,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(
                text = row.headlineUnit.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            caption(row)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.missedOnce,
                    fontWeight = FontWeight.Medium,
                )
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
        // A paused habit offers nothing to log. Leaving a live control here would invite
        // taps that quietly restart judging a habit the user deliberately suspended.
        row.isPaused -> {
            Icon(
                Icons.Default.PauseCircleOutline,
                contentDescription = "Paused",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }

        habit.polarity == Polarity.NEGATIVE -> {
            OutlinedButton(onClick = onLog, shape = RoundedCornerShape(14.dp)) {
                Text("I slipped")
            }
        }

        habit.cadence is Cadence.TimesPerWeek -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Weekly habits accumulate rather than toggle, so undo has to be explicit.
                if (row.currentCount > 0) {
                    OutlinedButton(
                        onClick = onUndo,
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(10.dp, 4.dp),
                    ) { Text("-") }
                }
                OutlinedButton(onClick = onLog, shape = RoundedCornerShape(14.dp)) {
                    Text("Log one")
                }
            }
        }

        else -> {
            CheckTile(satisfied = row.isSatisfied, onClick = onLog, doneColor = colors.done)
        }
    }
}

/**
 * The tap target for a daily habit.
 *
 * Springs when it fills. The reward for a habit arrives months later, so the app owes you
 * something satisfying at the exact moment you act - that is the whole of Clear's fourth
 * law, and it costs one animation.
 */
@Composable
private fun CheckTile(
    satisfied: Boolean,
    onClick: () -> Unit,
    doneColor: androidx.compose.ui.graphics.Color,
) {
    val background by animateColorAsState(
        targetValue = if (satisfied) doneColor else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "tile",
    )
    val pop by animateFloatAsState(
        targetValue = if (satisfied) 1f else 0.88f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "pop",
    )

    val shape = RoundedCornerShape(16.dp)
    // Cards are near-white, so an unfilled tile needs a real outline or it disappears
    // into the card and stops reading as something you can tap.
    val outline by animateColorAsState(
        targetValue =
            if (satisfied) doneColor else MosaicTheme.colors.todayOutline.copy(alpha = 0.55f),
        label = "outline",
    )

    Box(
        modifier =
            Modifier
                .size(52.dp)
                .scale(pop)
                .clip(shape)
                .background(background)
                .border(2.dp, outline, shape)
                // Toggleable rather than clickable, and labelled in both states. An
                // unticked tile is an empty box drawing nothing, so before this it reached
                // a screen reader as an unlabelled button - the one control on the home
                // screen that actually does something, announced as nothing at all. The
                // checkbox role carries done-or-not, so the label stays the same either way.
                .toggleable(
                    value = satisfied,
                    role = Role.Checkbox,
                    onValueChange = { onClick() },
                ).semantics { contentDescription = "Done today" },
        contentAlignment = Alignment.Center,
    ) {
        if (satisfied) {
            Icon(
                Icons.Default.Check,
                // Already covered by the tile's own label; repeating it here would make
                // the row announce it twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/** The supporting line under the hero number, when there is something worth saying. */
private fun caption(row: HabitRowState): String? =
    when {
        // Says why the row is quiet, so a paused habit does not read as a stalled one.
        row.isPaused -> "paused"

        row.stats.atRisk -> "don't miss twice"

        row.headlineCount == 0 -> "starting over"

        row.habit.strictness == Strictness.STRICT &&
            row.habit.polarity == Polarity.NEGATIVE -> "clean"

        else -> null
    }

private fun quotaLabel(
    row: HabitRowState,
    target: Int,
): String =
    when {
        row.habit.polarity == Polarity.NEGATIVE -> {
            "$target per week allowed - ${row.currentCount} used"
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
    val colors = MosaicTheme.colors
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // A chain that trails off, which is exactly what the app is asking you to start.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(6) { i ->
                    Box(
                        Modifier
                            .size(width = 22.dp, height = 14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.done.copy(alpha = 0.85f - i * 0.13f)),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Text("Start one chain", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                text =
                    "Pick something small enough that you can't talk yourself out of it. " +
                        "You can add more once this one sticks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
