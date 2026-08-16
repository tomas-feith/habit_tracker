package com.chainhabits.app.data

import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Where a new habit lands in the list.
 *
 * Habits are listed by `sortOrder, id` and a drag rewrites the list as 0..n-1, so a new
 * habit left at the default 0 ties with whatever sits at the top rather than going to the
 * bottom. That is the bug these cover.
 */
class SortOrderTest {
    private fun habit(
        id: Long,
        sortOrder: Int,
        archivedOn: LocalDate? = null,
    ) = HabitEntity(
        id = id,
        name = "Habit $id",
        note = null,
        polarity = Polarity.POSITIVE,
        strictness = Strictness.STANDARD,
        cadenceType = CadenceType.DAILY,
        cadenceDays = 0,
        cadenceTarget = 1,
        reminderMinuteOfDay = null,
        createdOn = LocalDate.parse("2026-01-01"),
        archivedOn = archivedOn,
        sortOrder = sortOrder,
    )

    @Test
    fun `the first habit takes position zero`() {
        assertEquals(0, nextSortOrder(emptyList()))
    }

    @Test
    fun `a new habit goes after a reordered list rather than into it`() {
        // The list as a drag leaves it: 0..2. The new habit must be 3, not 0.
        val reordered = listOf(habit(7, 0), habit(3, 1), habit(9, 2))
        assertEquals(3, nextSortOrder(reordered))
    }

    @Test
    fun `archived habits still hold their positions`() {
        // They are excluded from the visible list but not from the numbering; ignoring
        // them would hand the new habit a number that is already taken.
        val withArchived =
            listOf(habit(1, 0), habit(2, 5, archivedOn = LocalDate.parse("2026-05-01")))
        assertEquals(6, nextSortOrder(withArchived))
    }

    @Test
    fun `gaps in the numbering do not cause a collision`() {
        assertEquals(11, nextSortOrder(listOf(habit(1, 0), habit(2, 10))))
    }

    @Test
    fun `a list that never got reordered still appends`() {
        // Every habit created before this fix sits at 0; the next one must not join them.
        assertEquals(1, nextSortOrder(listOf(habit(1, 0), habit(2, 0), habit(3, 0))))
    }
}
