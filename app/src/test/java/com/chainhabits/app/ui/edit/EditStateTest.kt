package com.chainhabits.app.ui.edit

import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * What the edit form persists.
 *
 * The form deliberately does not model `sortOrder` or `archivedOn` - the user cannot edit
 * them - but `updateHabit` rewrites the whole row, so anything the form fails to carry is
 * silently reset. These tests exist because that went unnoticed: editing a habit's name
 * moved it to the top of the list.
 */
class EditStateTest {
    private val stored =
        Habit(
            id = 4,
            name = "Read",
            polarity = Polarity.POSITIVE,
            strictness = Strictness.STANDARD,
            cadence = Cadence.Daily,
            createdOn = LocalDate.parse("2026-01-01"),
            archivedOn = null,
            sortOrder = 6,
        )

    private val form =
        EditUiState(
            id = 4,
            name = "Read 20 pages",
            createdOn = LocalDate.parse("2026-01-01"),
        )

    @Test
    fun `an edit keeps the habit's place in the list`() {
        assertEquals(6, form.toHabitPreserving(stored).sortOrder)
    }

    @Test
    fun `an edit does not un-archive the habit`() {
        val archived = stored.copy(archivedOn = LocalDate.parse("2026-05-01"))
        assertEquals(
            LocalDate.parse("2026-05-01"),
            form.toHabitPreserving(archived).archivedOn,
        )
    }

    @Test
    fun `an edit still applies the fields the form does own`() {
        // The preservation must not go so far as to ignore the actual edit.
        val edited =
            form
                .copy(
                    name = "Read 30 pages",
                    note = "Fiction counts.",
                    polarity = Polarity.NEGATIVE,
                    strictness = Strictness.STRICT,
                    cadenceChoice = CadenceChoice.TIMES_PER_WEEK,
                    target = 4,
                    reminderTime = LocalTime.of(21, 30),
                ).toHabitPreserving(stored)

        assertEquals("Read 30 pages", edited.name)
        assertEquals("Fiction counts.", edited.note)
        assertEquals(Polarity.NEGATIVE, edited.polarity)
        assertEquals(Strictness.STRICT, edited.strictness)
        assertEquals(Cadence.TimesPerWeek(4), edited.cadence)
        assertEquals(LocalTime.of(21, 30), edited.reminderTime)
        // ...and still keeps the two it does not own.
        assertEquals(6, edited.sortOrder)
    }

    @Test
    fun `a new habit has no stored row to preserve from`() {
        val fresh = form.toHabitPreserving(null)
        assertEquals(0, fresh.sortOrder)
        assertNull(fresh.archivedOn)
    }

    @Test
    fun `the creation date is never rewritten by an edit`() {
        // Not part of the bug, but the same shape: a moved createdOn would silently
        // invent or destroy history for every day between.
        assertEquals(
            LocalDate.parse("2026-01-01"),
            form.toHabitPreserving(stored).createdOn,
        )
    }

    @Test
    fun `specific days survive the round trip`() {
        val days = setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)
        val edited =
            form
                .copy(cadenceChoice = CadenceChoice.SPECIFIC_DAYS, days = days)
                .toHabitPreserving(stored)
        assertEquals(Cadence.SpecificDays(days), edited.cadence)
    }
}
