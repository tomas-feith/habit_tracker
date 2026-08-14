package com.tsfeith.habits.ui.detail

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tsfeith.habits.domain.Cadence
import com.tsfeith.habits.domain.Habit
import com.tsfeith.habits.domain.Polarity
import com.tsfeith.habits.domain.Strictness
import com.tsfeith.habits.ui.components.MosaicLegend
import com.tsfeith.habits.ui.components.MosaicStrip
import com.tsfeith.habits.ui.components.YearMosaic
import com.tsfeith.habits.ui.theme.MosaicTheme
import kotlin.math.roundToInt

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
                    )
                }
                Spacer(Modifier.height(10.dp))
                MosaicLegend(strict = habit.strictness == Strictness.STRICT)
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
            style = MaterialTheme.typography.displayMedium,
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
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
    ) {
        Column(Modifier.padding(12.dp)) {
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
