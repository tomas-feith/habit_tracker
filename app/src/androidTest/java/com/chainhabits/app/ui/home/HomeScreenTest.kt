package com.chainhabits.app.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chainhabits.app.data.HabitDatabase
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import com.chainhabits.app.ui.theme.HabitTrackerTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * The home screen, driven end to end against a real database.
 *
 * A real repository rather than a stubbed view model: what is worth pinning here is that a
 * tap reaches storage and comes back through the flow as a changed tile, and a stub would
 * assert only that the screen calls the function the screen calls.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: HabitDatabase
    private lateinit var repository: HabitRepository

    private val today: LocalDate = LocalDate.now()

    @Before
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    HabitDatabase::class.java,
                ).build()
        repository = HabitRepository(db.habitDao())
    }

    @After
    fun close() {
        db.close()
    }

    private fun add(
        name: String,
        cadence: Cadence = Cadence.Daily,
        polarity: Polarity = Polarity.POSITIVE,
        strictness: Strictness = Strictness.STANDARD,
        createdDaysAgo: Long = 30,
    ): Long =
        runBlocking {
            repository.addHabit(
                Habit(
                    name = name,
                    polarity = polarity,
                    strictness = strictness,
                    cadence = cadence,
                    createdOn = today.minusDays(createdDaysAgo),
                ),
            )
        }

    private var opened: Long? = null

    private fun show() {
        // Built outside setContent: constructing a view model inside a composable is a
        // lint error, and it would also be rebuilt on every recomposition.
        val model = HomeViewModel(repository)
        compose.setContent {
            HabitTrackerTheme {
                HomeScreen(
                    onAddHabit = {},
                    onOpenHabit = { opened = it },
                    onOpenBackup = {},
                    viewModel = model,
                )
            }
        }
    }

    /** Waits for text that arrives via a database flow rather than the click itself. */
    private fun awaitText(text: String) =
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun tileCount(): Int =
        compose.onAllNodesWithContentDescription("Done today").fetchSemanticsNodes().size

    /**
     * Waits for the tile itself to settle, not for the row that caused it.
     *
     * Waiting on the database and then asserting on the tile is a race: the write lands
     * first and the flow, the view model and the recomposition all happen after it. A fast
     * phone hides that and a CI emulator does not.
     */
    private fun awaitTile(on: Boolean) =
        compose.waitUntil(TIMEOUT) {
            compose
                .onAllNodes(hasContentDescription("Done today") and if (on) isOn() else isOff())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

    @Test
    fun anEmptyLibraryInvitesTheFirstHabit() {
        show()
        awaitText("Start one chain")
        compose.onNodeWithText("Start one chain").assertExists()
    }

    @Test
    fun aHabitShowsItsNameAndItsChain() {
        val id = add("Read")
        runBlocking {
            val habit = repository.getHabit(id)!!
            repository.logEvent(habit, today.minusDays(1))
            repository.logEvent(habit, today)
        }

        show()
        awaitText("Read")
        compose.onNodeWithText("Read").assertExists()
        // Two consecutive good days, today included.
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("DAYS").assertExists()
    }

    @Test
    fun tappingTheTileLogsTodayAndTappingAgainUndoesIt() {
        add("Read")
        show()
        awaitText("Read")

        compose.onNodeWithContentDescription("Done today").assertIsOff()

        compose.onNodeWithContentDescription("Done today").performClick()
        awaitTile(on = true)
        assertEquals(
            "the tap must reach storage, not just the tile",
            1,
            runBlocking { loggedToday() },
        )

        // A daily habit toggles: the same tap undoes it.
        compose.onNodeWithContentDescription("Done today").performClick()
        awaitTile(on = false)
        assertEquals(0, runBlocking { loggedToday() })
    }

    private suspend fun loggedToday(): Int =
        db
            .habitDao()
            .allEntries()
            .filter { it.date == today }
            .sumOf { it.count }

    @Test
    fun aNegativeHabitOffersASlipButtonRatherThanACheckbox() {
        add("No takeaway", polarity = Polarity.NEGATIVE)
        show()
        awaitText("No takeaway")

        // Tapping a checked box to record a failure is backwards, and far too easy to hit.
        compose.onNodeWithText("I slipped").assertExists()
        assertEquals("a negative habit must not show a tick tile", 0, tileCount())
    }

    @Test
    fun aWeeklyHabitAccumulatesInsteadOfToggling() {
        add("Workout", cadence = Cadence.TimesPerWeek(3))
        show()
        awaitText("Workout")

        compose.onNodeWithText("Log one").performClick()
        awaitText("1 of 3 this week")
        compose.onNodeWithText("Log one").performClick()
        awaitText("2 of 3 this week")

        // The fourth workout in a 3x week is a fourth workout, not an undo of the third.
        assertEquals(2, runBlocking { loggedToday() })
    }

    @Test
    fun theNeverMissTwiceBannerAppearsAfterASingleMiss() {
        val id = add("Read", createdDaysAgo = 10)
        runBlocking {
            // Good until the day before yesterday, then one miss. Yesterday is the last
            // settled period, so the chain is intact but at risk.
            val habit = repository.getHabit(id)!!
            (2L..9L).forEach { repository.logEvent(habit, today.minusDays(it)) }
        }

        show()
        awaitText("Never miss twice")
        compose.onNodeWithText("don't miss twice").assertExists()
    }

    @Test
    fun aPausedHabitReadsAsSuspendedAndOffersNothingToLog() {
        val id = add("Read")
        runBlocking { repository.pauseHabit(id, today) }

        show()
        awaitText("paused")
        // Leaving a live control would invite taps that quietly restart judging a habit
        // the user deliberately suspended.
        assertEquals(0, tileCount())
    }

    @Test
    fun tappingTheCardOpensThatHabit() {
        val id = add("Read")
        show()
        awaitText("Read")

        compose.onNodeWithText("Read").performClick()

        assertEquals(id, opened)
    }

    @Test
    fun theMosaicIsDescribedRatherThanLeftAsABareCanvas() {
        // Created today, so the strip holds exactly one cell and the summary is exact.
        val id = add("Read", createdDaysAgo = 0)
        runBlocking { repository.logEvent(repository.getHabit(id)!!, today) }

        show()
        awaitText("Read")

        // The strip is a Canvas, so without this the app's entire progress display is
        // invisible to a screen reader.
        compose
            .onNodeWithContentDescription("1 good and 0 missed over the last 1 recorded periods")
            .assertExists()
    }

    /**
     * The picker pauses what was chosen and nothing else.
     *
     * The point of it: going away rarely suspends everything - the gym stops, the reading
     * does not - and the switch this replaced could only say "all" or "none".
     */
    @Test
    fun thePickerPausesOnlyTheHabitsChosen() {
        val read = add("Read")
        add("Workout")
        show()
        awaitText("Read")

        compose.onNodeWithContentDescription("Pause habits").performClick()
        awaitText("Pause habits")
        pickerRow("Read").performClick()
        compose.onNodeWithText("Done").performClick()

        awaitText("paused")
        assertEquals(listOf(read), runBlocking { db.habitDao().pausedHabitIds() })
    }

    @Test
    fun thePickerRecordsWhyAndTheRowSaysIt() {
        add("Read")
        show()
        awaitText("Read")

        compose.onNodeWithContentDescription("Pause habits").performClick()
        awaitText("Pause habits")
        compose.onNodeWithText("All").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("On vacation")
        compose.onNodeWithText("Done").performClick()

        // The reason is the whole point of the field: "paused" alone only raises the
        // question it already has the answer to.
        awaitText("paused - On vacation")
    }

    @Test
    fun unpickingAHabitInThePickerResumesIt() {
        val id = add("Read")
        runBlocking { repository.pauseHabit(id, today, "On vacation") }
        show()
        awaitText("paused - On vacation")

        compose.onNodeWithContentDescription("Pause habits").performClick()
        awaitText("Pause habits")
        // It opens showing what is already true, so resuming is unticking.
        pickerRow("Read").performClick()
        compose.onNodeWithText("Done").performClick()

        // The tile coming back is the habit being judged again, which is the real change.
        awaitTile(on = false)
        assertNull(runBlocking { db.habitDao().pausedHabitIds().firstOrNull() })
    }

    /**
     * A habit paused while the dialog sits open must not be resumed by closing it.
     *
     * The dialog holds a snapshot from the moment it opened - the widget, a reminder or
     * midnight can all move the database under it - so it applies what the user actually
     * ticked and unticked rather than treating that snapshot as the whole truth.
     */
    @Test
    fun thePickerLeavesHabitsTheUserNeverTouchedAlone() {
        val read = add("Read")
        val workout = add("Workout")
        show()
        awaitText("Read")

        compose.onNodeWithContentDescription("Pause habits").performClick()
        awaitText("Pause habits")
        pickerRow("Read").performClick()

        // Something else pauses Workout while the dialog is open.
        runBlocking { repository.pauseHabit(workout, today, "Injured leg") }

        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(TIMEOUT) {
            runBlocking { db.habitDao().pausedHabitIds() }.size == 2
        }

        assertEquals(
            setOf(read, workout),
            runBlocking { db.habitDao().pausedHabitIds() }.toSet(),
        )
        // Untouched means untouched: its own message is still on it.
        assertEquals("Injured leg", runBlocking { db.habitDao().openPauseFor(workout) }?.reason)
        compose.onNodeWithText("Pause habits").assertDoesNotExist()
    }

    /**
     * The picker's line for one habit.
     *
     * Matched on being toggleable, because the card behind the dialog carries the same
     * name and only the picker's line is a checkbox.
     */
    private fun pickerRow(name: String) = compose.onNode(hasText(name) and isToggleable())

    // --- The record caption -------------------------------------------------------
    //
    // Driven through the real evaluator rather than a stubbed HabitStats, because the bug
    // these cover was in how the stats were *derived*: longestStreak counts the run in
    // progress, so a hand-built stats object would happily assert a record the real
    // evaluator could never produce.
    //
    // Only the caption is covered. The other half of the celebration - the pulse on the
    // hero number and its haptic - carries no semantics and is invisible to this harness.
    // That is also why the caption exists: it is the part of the flourish a screen reader
    // can reach.

    /** Logs [days] as offsets back from today, so the runs read the way they are described. */
    private fun log(
        id: Long,
        days: Iterable<Long>,
    ) = runBlocking {
        val habit = repository.getHabit(id)!!
        days.forEach { repository.logEvent(habit, today.minusDays(it)) }
    }

    @Test
    fun aRunPastItsPreviousBestIsCalledTheBestEver() {
        val id = add("Read", createdDaysAgo = 20)
        // A five-day run that ends in two misses, then fourteen days up to today.
        log(id, (16L..20L))
        log(id, (0L..13L))

        show()
        awaitText("Read")

        // 14 beats the 5 that already ended, so this is genuinely a new best.
        compose.onNodeWithText("14").assertIsDisplayed()
        compose.onNodeWithText("best ever").assertIsDisplayed()
    }

    @Test
    fun merelyEquallingThePreviousBestIsNotCalledTheBestEver() {
        val id = add("Read", createdDaysAgo = 20)
        // A five-day run that ended, and a five-day run in progress. Exactly equal.
        log(id, (16L..20L))
        log(id, (0L..4L))

        show()
        awaitText("Read")
        compose.onNodeWithText("5").assertIsDisplayed()

        // The bug this pins: longestStreak includes the live run, so it reads 5 here just
        // as it does when the record really is being broken. Comparing against it called
        // this a record.
        compose.onNodeWithText("best ever").assertDoesNotExist()
    }

    @Test
    fun theFirstDayOfABrandNewHabitIsNotCalledTheBestEver() {
        val id = add("Read", createdDaysAgo = 0)
        log(id, listOf(0L))

        show()
        awaitText("Read")
        compose.onNodeWithText("1").assertIsDisplayed()

        // Trivially the best it has ever done, and worth nothing.
        compose.onNodeWithText("best ever").assertDoesNotExist()
    }

    @Test
    fun aFirstRunIsCalledTheBestEverOnceItIsWorthSaying() {
        val id = add("Read", createdDaysAgo = 2)
        log(id, (0L..2L))

        show()
        awaitText("Read")

        // Nothing broken behind it, so there is no previous best to beat - the floor is
        // what makes this the first moment worth marking.
        compose.onNodeWithText("3").assertIsDisplayed()
        compose.onNodeWithText("best ever").assertIsDisplayed()
    }

    @Test
    fun aNegativeHabitIsStillToldWhenItIsAtItsBest() {
        // Never slipped since it was created, so every settled day is clean.
        add("No takeaway", polarity = Polarity.NEGATIVE, strictness = Strictness.STRICT)

        show()
        awaitText("No takeaway")

        // The caption is not gated on polarity even though the flourish is: a negative
        // habit's record arrives at midnight rather than in response to anything the user
        // did, which is a reason not to buzz about it, not a reason to hide it.
        compose.onNodeWithText("best ever").assertIsDisplayed()
    }

    @Test
    fun aNegativeHabitRebuildingAfterASlipReadsAsCleanRatherThanBest() {
        val id = add("No takeaway", polarity = Polarity.NEGATIVE, strictness = Strictness.STRICT)
        log(id, listOf(1L))

        show()
        awaitText("No takeaway")

        // Yesterday's slip ended a long clean run, so the day since it is nowhere near a
        // record and the row falls back to saying what it is rather than what it achieved.
        compose.onNodeWithText("clean").assertIsDisplayed()
        compose.onNodeWithText("best ever").assertDoesNotExist()
    }

    @Test
    fun aPausedHabitIsNotCongratulatedForStandingStill() {
        val id = add("Read", createdDaysAgo = 20)
        log(id, (0L..13L))
        runBlocking { repository.pauseHabit(id, today) }

        show()
        awaitText("paused")

        // A record held by a suspended habit is not being set by anything the user is
        // doing, so there is nothing to celebrate.
        compose.onNodeWithText("best ever").assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
