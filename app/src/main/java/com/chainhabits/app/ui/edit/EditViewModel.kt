package com.chainhabits.app.ui.edit

import android.content.Context
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

enum class CadenceChoice { DAILY, SPECIFIC_DAYS, TIMES_PER_WEEK }

data class EditUiState(
    val id: Long = 0,
    val name: String = "",
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

    fun toHabit() =
        Habit(
            id = id,
            name = name.trim(),
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

class EditViewModel(
    private val repository: HabitRepository,
    private val habitId: Long,
) : ViewModel() {
    private val _state = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = _state.asStateFlow()

    val isNew: Boolean get() = habitId <= 0

    init {
        if (!isNew) {
            viewModelScope.launch {
                repository.getHabit(habitId)?.let { h ->
                    _state.update {
                        EditUiState(
                            id = h.id,
                            name = h.name,
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

    fun setName(v: String) = _state.update { it.copy(name = v) }

    fun setPolarity(v: Polarity) = _state.update { it.copy(polarity = v) }

    fun setStrictness(v: Strictness) = _state.update { it.copy(strictness = v) }

    fun setCadence(v: CadenceChoice) = _state.update { it.copy(cadenceChoice = v) }

    fun setTarget(v: Int) =
        _state.update { it.copy(target = v.coerceIn(MIN_WEEKLY_TARGET, MAX_WEEKLY_TARGET)) }

    fun setReminder(v: LocalTime?) = _state.update { it.copy(reminderTime = v) }

    fun toggleDay(day: DayOfWeek) =
        _state.update {
            val next = if (day in it.days) it.days - day else it.days + day
            // Never let the set empty out - a habit scheduled on no days can never be done.
            it.copy(days = next.ifEmpty { it.days })
        }

    fun save(context: Context) =
        viewModelScope.launch {
            val habit = _state.value.toHabit()
            val saved =
                if (isNew) {
                    habit.copy(id = repository.addHabit(habit))
                } else {
                    repository.updateHabit(habit)
                    habit
                }

            if (saved.reminderTime != null) {
                Reminders.schedule(context, saved)
            } else {
                Reminders.cancel(context, saved.id)
            }

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
