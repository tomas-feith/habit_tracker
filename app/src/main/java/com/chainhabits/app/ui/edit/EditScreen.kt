package com.chainhabits.app.ui.edit

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.text.intl.Locale as ComposeLocale

/** Show the character counter only in the last stretch before the cap. */
private const val NOTE_COUNTER_THRESHOLD = MAX_NOTE_LENGTH - 40

private val DEFAULT_REMINDER = LocalTime.of(8, 0)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    viewModel: EditViewModel,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNew) "New habit" else "Edit habit") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!viewModel.isNew) {
                        IconButton(onClick = { viewModel.delete(context) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete habit")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("Habit") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            NoteField(state, viewModel)

            KindField(state, viewModel)
            CadenceField(state, viewModel)
            StrictnessField(state, viewModel)
            ReminderField(state, viewModel, context)

            Button(
                onClick = { viewModel.save(context) },
                enabled = state.isValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.isNew) "Create habit" else "Save")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The optional note.
 *
 * Deliberately multi-line and un-cramped: the useful thing to write here is why the habit
 * matters or what counts as done, and a single-line box invites a label instead.
 */
@Composable
private fun NoteField(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    // Null rather than a lambda that renders nothing: the supporting-text slot reserves
    // its own padding whether or not it draws anything, so an always-present slot would
    // leave this one field spaced differently from every other control on the screen.
    val counter: (@Composable () -> Unit)? =
        if (state.note.length >= NOTE_COUNTER_THRESHOLD) {
            { Text("${state.note.length} / $MAX_NOTE_LENGTH") }
        } else {
            null
        }

    OutlinedTextField(
        value = state.note,
        onValueChange = viewModel::setNote,
        label = { Text("Note") },
        placeholder = { Text("Why this matters, or what counts as done") },
        minLines = 2,
        maxLines = 5,
        supportingText = counter,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KindField(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    Field(
        title = "Kind",
        help =
            when (state.polarity) {
                Polarity.POSITIVE -> "You log completions. An untouched day becomes a miss."
                Polarity.NEGATIVE -> "You log slips. An untouched day stays clean."
            },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.polarity == Polarity.POSITIVE,
                onClick = { viewModel.setPolarity(Polarity.POSITIVE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Build") }
            SegmentedButton(
                selected = state.polarity == Polarity.NEGATIVE,
                onClick = { viewModel.setPolarity(Polarity.NEGATIVE) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Break") }
        }
    }
}

@Composable
private fun CadenceField(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    Field(title = "How often", help = cadenceHelp(state)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            CadenceChoice.entries.forEachIndexed { i, choice ->
                SegmentedButton(
                    selected = state.cadenceChoice == choice,
                    onClick = { viewModel.setCadence(choice) },
                    shape = SegmentedButtonDefaults.itemShape(i, CadenceChoice.entries.size),
                ) {
                    Text(
                        when (choice) {
                            CadenceChoice.DAILY -> "Daily"
                            CadenceChoice.SPECIFIC_DAYS -> "Days"
                            CadenceChoice.TIMES_PER_WEEK -> "Weekly"
                        },
                    )
                }
            }
        }

        when (state.cadenceChoice) {
            CadenceChoice.SPECIFIC_DAYS -> {
                Spacer(Modifier.height(10.dp))
                DayPicker(state, viewModel)
            }

            CadenceChoice.TIMES_PER_WEEK -> {
                Spacer(Modifier.height(10.dp))
                TargetStepper(state, viewModel)
            }

            // Daily habits need no extra control.
            CadenceChoice.DAILY -> {}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPicker(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    // Read through Compose's locale, not Locale.getDefault(): the latter is invisible to
    // recomposition, so day names would keep the old language after a locale change.
    val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(
                selected = day in state.days,
                onClick = { viewModel.toggleDay(day) },
                label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
            )
        }
    }
}

@Composable
private fun TargetStepper(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { viewModel.setTarget(state.target - 1) },
            enabled = state.target > MIN_WEEKLY_TARGET,
        ) { Text("-") }
        Text(
            text = "  ${state.target}x per week  ",
            style = MaterialTheme.typography.titleMedium,
        )
        OutlinedButton(
            onClick = { viewModel.setTarget(state.target + 1) },
            enabled = state.target < MAX_WEEKLY_TARGET,
        ) { Text("+") }
    }
}

@Composable
private fun StrictnessField(
    state: EditUiState,
    viewModel: EditViewModel,
) {
    Field(
        title = "After a miss",
        help =
            when (state.strictness) {
                Strictness.STANDARD -> {
                    "Never miss twice: one miss is recoverable, two in a row breaks the chain."
                }

                Strictness.STRICT -> {
                    "One miss breaks the chain immediately. For habits where the single " +
                        "instance is the harm, not a data point."
                }
            },
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.strictness == Strictness.STANDARD,
                onClick = { viewModel.setStrictness(Strictness.STANDARD) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Standard") }
            SegmentedButton(
                selected = state.strictness == Strictness.STRICT,
                onClick = { viewModel.setStrictness(Strictness.STRICT) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Strict") }
        }
    }
}

@Composable
private fun ReminderField(
    state: EditUiState,
    viewModel: EditViewModel,
    context: Context,
) {
    Field(title = "Reminder", help = "A single daily nudge. Optional.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                val initial = state.reminderTime ?: DEFAULT_REMINDER
                TimePickerDialog(
                    context,
                    { _, hour, minute -> viewModel.setReminder(LocalTime.of(hour, minute)) },
                    initial.hour,
                    initial.minute,
                    true,
                ).show()
            }) {
                Text(state.reminderTime?.format(TIME_FORMAT) ?: "Set a time")
            }
            if (state.reminderTime != null) {
                TextButton(onClick = { viewModel.setReminder(null) }) { Text("Clear") }
            }
        }
    }
}

/** A labelled control with a line of explanatory text underneath. */
@Composable
private fun Field(
    title: String,
    help: String,
    content: @Composable () -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(6.dp))
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun cadenceHelp(state: EditUiState): String =
    when (state.cadenceChoice) {
        CadenceChoice.DAILY -> {
            "Every day counts. One mosaic cell is one day."
        }

        CadenceChoice.SPECIFIC_DAYS -> {
            "Only the chosen days count; the rest show as off, not as failures."
        }

        CadenceChoice.TIMES_PER_WEEK -> {
            if (state.polarity == Polarity.NEGATIVE) {
                "An allowance of ${state.target} per week. One mosaic cell is one week."
            } else {
                "Any ${state.target} days in the week. No single day can fail - " +
                    "one cell is one week."
            }
        }
    }
