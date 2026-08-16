package com.chainhabits.app.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.CellState
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The backfill controls as a screen reader sees them.
 *
 * The heatmap's own cells are a bare Canvas: no semantics, and 12dp targets. This list is
 * the reachable route to the same edit, so its semantics *are* the feature rather than a
 * decoration on it.
 *
 * Written after a fix that looked right and was not: wrapping the label in
 * `semantics(mergeDescendants = true)` compiled, read sensibly, and still left an
 * unlabelled non-focusable node with both texts exposed beneath it. Nothing but reading
 * the accessibility tree caught that, and nothing but this would catch it coming back.
 */
@RunWith(AndroidJUnit4::class)
class BackfillSheetTest {
    // An Activity-backed rule, like the other screen tests. The bare createComposeRule
    // intermittently found no compose hierarchy at all once these suites shared a process.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today: LocalDate = LocalDate.parse("2026-08-16")

    private fun habit(cadence: Cadence = Cadence.Daily) =
        Habit(
            id = 1,
            name = "Read",
            polarity = Polarity.POSITIVE,
            cadence = cadence,
            createdOn = today.minusMonths(1),
        )

    private fun day(
        date: LocalDate,
        count: Int,
        state: CellState = CellState.PENDING,
    ) = DaySelection(date = date, count = count, state = state, editable = true)

    @Test
    fun eachDayIsOneToggleableNodeCarryingItsOwnLabel() {
        compose.setContent {
            BackfillDays(
                habit = habit(),
                days =
                    listOf(
                        day(today, count = 0),
                        day(today.minusDays(1), count = 1, state = CellState.DONE),
                    ),
                today = today,
                onSetCount = { _, _ -> },
            )
        }

        // The row itself is the control, so the date and the checkbox are one stop.
        compose.onNodeWithText("Today").assertIsToggleable()
        compose.onNodeWithText("Today").assertIsOff()
        compose.onNodeWithText("Yesterday").assertIsToggleable()
        compose.onNodeWithText("Yesterday").assertIsOn()
    }

    @Test
    fun togglingADayReportsTheDateAndTheNewCount() {
        var written: Pair<LocalDate, Int>? = null
        compose.setContent {
            BackfillDays(
                habit = habit(),
                days = listOf(day(today.minusDays(2), count = 0)),
                today = today,
                onSetCount = { date, count -> written = date to count },
            )
        }

        compose.onNodeWithText("Friday 14 August").performClick()

        assertEquals(today.minusDays(2) to 1, written)
    }

    @Test
    fun clearingADayWritesZeroRatherThanStepping() {
        var written: Pair<LocalDate, Int>? = null
        compose.setContent {
            BackfillDays(
                habit = habit(),
                days = listOf(day(today, count = 1, state = CellState.DONE)),
                today = today,
                onSetCount = { date, count -> written = date to count },
            )
        }

        compose.onNodeWithText("Today").performClick()

        assertEquals(today to 0, written)
    }

    @Test
    fun countRowsNameTheDayInEveryButton() {
        compose.setContent {
            BackfillDays(
                habit = habit(Cadence.TimesPerWeek(3)),
                days = listOf(day(today, count = 2), day(today.minusDays(1), count = 0)),
                today = today,
                onSetCount = { _, _ -> },
            )
        }

        // A bare "add" would be useless: these repeat down the list, and a screen reader
        // reaches them one after another with no idea which day each belongs to.
        compose.onNodeWithContentDescription("One more on Today").assertExists()
        compose.onNodeWithContentDescription("One fewer on Today").assertExists()
        compose.onNodeWithContentDescription("One more on Yesterday").assertExists()
        compose.onNodeWithContentDescription("One fewer on Yesterday").assertExists()
    }

    @Test
    fun theDateAndItsStatusArriveAsASingleLabel() {
        // The mergeDescendants regression: these must be one node, not two stops.
        compose.setContent {
            BackfillDays(
                habit = habit(Cadence.TimesPerWeek(3)),
                days = listOf(day(today, count = 2)),
                today = today,
                onSetCount = { _, _ -> },
            )
        }

        compose.onNodeWithContentDescription("Today. 2 logged").assertExists()
    }

    @Test
    fun countButtonsStepFromTheDaysOwnCount() {
        var written: Pair<LocalDate, Int>? = null
        compose.setContent {
            BackfillDays(
                habit = habit(Cadence.TimesPerWeek(3)),
                days = listOf(day(today, count = 2)),
                today = today,
                onSetCount = { date, count -> written = date to count },
            )
        }

        compose.onNodeWithContentDescription("One more on Today").performClick()
        assertEquals(today to 3, written)

        compose.onNodeWithContentDescription("One fewer on Today").performClick()
        assertEquals(today to 1, written)
    }
}
