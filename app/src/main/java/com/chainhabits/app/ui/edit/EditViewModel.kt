package com.chainhabits.app.ui.edit

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import com.chainhabits.app.notify.Reminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** A weekly target below one would mean the habit can never be satisfied. */
const val MIN_WEEKLY_TARGET = 1

/** Above seven a weekly target is just a daily habit with extra steps. */
const val MAX_WEEKLY_TARGET = 7

/**
 * A note is a reminder of intent, not a journal - and the detail screen renders it in full
 * with no truncation, so an unbounded paste would push the mosaic off the screen.
 */
const val MAX_NOTE_LENGTH = 280

/**
 * Like [take], but never splits a surrogate pair.
 *
 * A Kotlin String is UTF-16, so `take` counts code units: cutting a 280-char paste whose
 * 280th unit is the leading half of an emoji leaves a lone surrogate behind, which renders
 * as a tofu box and is then written to the database that way. Dropping the orphaned half
 * costs one character and keeps the string well-formed.
 */
internal fun String.takeChars(max: Int): String {
    if (length <= max) return this
    val end = if (this[max - 1].isHighSurrogate()) max - 1 else max
    return substring(0, end)
}

enum class CadenceChoice { DAILY, SPECIFIC_DAYS, TIMES_PER_WEEK }

/**
 * Keys for the in-progress form held in [SavedStateHandle].
 *
 * Stored as primitives rather than one serialized blob: `SavedStateHandle` is backed by a
 * `Bundle`, which has no idea what a `LocalTime` or a `Set<DayOfWeek>` is, and a
 * hand-written encoding is easier to reason about than adding Parcelize plus custom
 * parcelers for three java.time types.
 */
private object DraftKeys {
    const val NAME = "draft.name"
    const val NOTE = "draft.note"
    const val POLARITY = "draft.polarity"
    const val STRICTNESS = "draft.strictness"
    const val CADENCE = "draft.cadence"
    const val DAYS = "draft.days"
    const val TARGET = "draft.target"
    const val REMINDER = "draft.reminderMinute"
    const val CREATED_ON = "draft.createdOn"
    const val ID = "draft.id"
}

/** Sentinel for "no reminder", since the handle stores a plain Int. */
private const val NO_REMINDER = -1

private fun SavedStateHandle.hasDraft(): Boolean = contains(DraftKeys.NAME)

private fun SavedStateHandle.writeDraft(state: EditUiState) {
    this[DraftKeys.NAME] = state.name
    this[DraftKeys.NOTE] = state.note
    this[DraftKeys.POLARITY] = state.polarity.name
    this[DraftKeys.STRICTNESS] = state.strictness.name
    this[DraftKeys.CADENCE] = state.cadenceChoice.name
    this[DraftKeys.DAYS] = state.days.map { it.value }.toIntArray()
    this[DraftKeys.TARGET] = state.target
    this[DraftKeys.REMINDER] = state.reminderTime?.toSecondOfDay() ?: NO_REMINDER
    this[DraftKeys.CREATED_ON] = state.createdOn.toEpochDay()
    this[DraftKeys.ID] = state.id
}

private fun SavedStateHandle.readDraft(): EditUiState? {
    val name: String = get<String>(DraftKeys.NAME) ?: return null
    val default = EditUiState()
    val days = get<IntArray>(DraftKeys.DAYS)?.map(DayOfWeek::of)?.toSet()
    val reminder =
        (get<Int>(DraftKeys.REMINDER) ?: NO_REMINDER)
            .takeIf { it != NO_REMINDER }
            ?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    return EditUiState(
        id = get<Long>(DraftKeys.ID) ?: default.id,
        name = name,
        note = get<String>(DraftKeys.NOTE) ?: default.note,
        polarity = get<String>(DraftKeys.POLARITY)?.let(Polarity::valueOf) ?: default.polarity,
        strictness =
            get<String>(DraftKeys.STRICTNESS)?.let(Strictness::valueOf) ?: default.strictness,
        cadenceChoice =
            get<String>(DraftKeys.CADENCE)?.let(CadenceChoice::valueOf) ?: default.cadenceChoice,
        // An empty set would make the habit impossible to ever complete.
        days = days?.ifEmpty { default.days } ?: default.days,
        target = get<Int>(DraftKeys.TARGET) ?: default.target,
        reminderTime = reminder,
        createdOn =
            get<Long>(DraftKeys.CREATED_ON)?.let(LocalDate::ofEpochDay) ?: default.createdOn,
    )
}

data class EditUiState(
    val id: Long = 0,
    val name: String = "",
    /** Always a plain String in the form; only the saved [Habit] distinguishes null. */
    val note: String = "",
    val polarity: Polarity = Polarity.POSITIVE,
    val strictness: Strictness = Strictness.STANDARD,
    val cadenceChoice: CadenceChoice = CadenceChoice.DAILY,
    val days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    val target: Int = 3,
    val reminderTime: LocalTime? = null,
    val createdOn: LocalDate = LocalDate.now(),
    val saved: Boolean = false,
) {
    val isValid: Boolean get() = name.isNotBlank()

    /**
     * The habit to persist: this form's fields, with the ones it does not own taken from
     * [stored] - the habit as it currently sits in the database, or null when creating.
     *
     * `sortOrder` and `archivedOn` are storage state, not form state. The form cannot edit
     * them and so does not carry them, which means [toHabit] alone produces a habit with
     * both at their defaults - and `updateHabit` rewrites the whole row, so saving an edit
     * would quietly reset the habit's place in the list and un-archive it.
     *
     * Read back at save time rather than held in [EditUiState], for two reasons: the form
     * stays a description of what the user can actually change, and a draft restored after
     * process death cannot resurface a stale position.
     */
    fun toHabitPreserving(stored: Habit?): Habit =
        toHabit().copy(
            sortOrder = stored?.sortOrder ?: 0,
            archivedOn = stored?.archivedOn,
        )

    fun toHabit() =
        Habit(
            id = id,
            name = name.trim(),
            note = note.trim().ifEmpty { null },
            polarity = polarity,
            strictness = strictness,
            cadence =
                when (cadenceChoice) {
                    CadenceChoice.DAILY -> Cadence.Daily
                    CadenceChoice.SPECIFIC_DAYS -> Cadence.SpecificDays(days)
                    CadenceChoice.TIMES_PER_WEEK -> Cadence.TimesPerWeek(target)
                },
            reminderTime = reminderTime,
            createdOn = createdOn,
        )
}

/**
 * Backs the habit form.
 *
 * The in-progress form is mirrored into [SavedStateHandle] on every keystroke, because a
 * half-written habit is real work and the system can kill the process the moment the app is
 * backgrounded - aggressively so on some OEM builds. Navigation restores the *destination*
 * by itself; without this the user would come back to the right screen with an empty form.
 */
class EditViewModel(
    private val repository: HabitRepository,
    private val habitId: Long,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(handle.readDraft() ?: EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    val isNew: Boolean get() = habitId <= 0

    init {
        // Only load from the database when there is no draft to restore - otherwise
        // returning to a half-edited habit would silently discard the edits.
        if (!isNew && !handle.hasDraft()) {
            viewModelScope.launch {
                repository.getHabit(habitId)?.let { h ->
                    edit {
                        EditUiState(
                            id = h.id,
                            name = h.name,
                            note = h.note.orEmpty(),
                            polarity = h.polarity,
                            strictness = h.strictness,
                            cadenceChoice =
                                when (h.cadence) {
                                    is Cadence.Daily -> CadenceChoice.DAILY
                                    is Cadence.SpecificDays -> CadenceChoice.SPECIFIC_DAYS
                                    is Cadence.TimesPerWeek -> CadenceChoice.TIMES_PER_WEEK
                                },
                            days = (h.cadence as? Cadence.SpecificDays)?.days ?: it.days,
                            target = (h.cadence as? Cadence.TimesPerWeek)?.target ?: it.target,
                            reminderTime = h.reminderTime,
                            createdOn = h.createdOn,
                        )
                    }
                }
            }
        }
    }

    /** Applies a change and mirrors the result into saved state, so a draft survives death. */
    private fun edit(block: (EditUiState) -> EditUiState) =
        _state.update { current -> block(current).also(handle::writeDraft) }

    fun setName(v: String) = edit { it.copy(name = v) }

    // Truncate rather than reject, so a paste that is slightly too long still lands.
    fun setNote(v: String) = edit { it.copy(note = v.takeChars(MAX_NOTE_LENGTH)) }

    /**
     * Switching to a negative habit drops any reminder time with it.
     *
     * A negative habit gets no nudge - there is no action it could prompt, see
     * [com.chainhabits.app.domain.ReminderDecision]. Clearing the time rather than merely
     * hiding the control keeps the stored habit honest: a reminder time that will never
     * fire, waiting to come back if the kind is flipped again, is a state nobody asked for.
     */
    fun setPolarity(v: Polarity) =
        edit {
            it.copy(
                polarity = v,
                reminderTime = if (v == Polarity.NEGATIVE) null else it.reminderTime,
            )
        }

    fun setStrictness(v: Strictness) = edit { it.copy(strictness = v) }

    fun setCadence(v: CadenceChoice) = edit { it.copy(cadenceChoice = v) }

    fun setTarget(v: Int) =
        edit { it.copy(target = v.coerceIn(MIN_WEEKLY_TARGET, MAX_WEEKLY_TARGET)) }

    fun setReminder(v: LocalTime?) = edit { it.copy(reminderTime = v) }

    fun toggleDay(day: DayOfWeek) =
        edit {
            val next = if (day in it.days) it.days - day else it.days + day
            // Never let the set empty out - a habit scheduled on no days can never be done.
            it.copy(days = next.ifEmpty { it.days })
        }

    fun save(context: Context) =
        viewModelScope.launch {
            val saved =
                if (isNew) {
                    // The repository picks the sort order for a new habit; the form has none.
                    val habit = _state.value.toHabit()
                    habit.copy(id = repository.addHabit(habit))
                } else {
                    val habit = _state.value.toHabitPreserving(repository.getHabit(habitId))
                    repository.updateHabit(habit)
                    habit
                }

            // Cancel first: this habit may have just lost its reminder or moved to another
            // time, and rescheduleAll only ever arms, so nothing else would clear the old
            // alarm. Re-arming everything is what keeps the stagger correct when this save
            // added a habit to, or took one out of, a group sharing a time.
            Reminders.cancel(context, saved.id)
            Reminders.rescheduleAll(context)

            _state.update { it.copy(saved = true) }
        }

    fun delete(context: Context) =
        viewModelScope.launch {
            if (isNew) return@launch
            Reminders.cancel(context, habitId)
            repository.getHabit(habitId)?.let { repository.deleteHabit(it) }
            _state.update { it.copy(saved = true) }
        }
}
