package com.chainhabits.app.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        compose.setContent {
            HabitTrackerTheme {
                HomeScreen(
                    onAddHabit = {},
                    onOpenHabit = { opened = it },
                    onOpenBackup = {},
                    viewModel = HomeViewModel(repository),
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
        compose.waitUntil(TIMEOUT) { runBlocking { loggedToday() } == 1 }
        compose.onNodeWithContentDescription("Done today").assertIsOn()

        // A daily habit toggles: the same tap undoes it.
        compose.onNodeWithContentDescription("Done today").performClick()
        compose.waitUntil(TIMEOUT) { runBlocking { loggedToday() } == 0 }
        compose.onNodeWithContentDescription("Done today").assertIsOff()
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

    @Test
    fun theHolidaySwitchPausesAndResumesEverything() {
        add("Read")
        add("Workout")
        show()
        awaitText("Read")

        compose.onNodeWithContentDescription("Pause all habits").performClick()
        compose.waitUntil(TIMEOUT) { runBlocking { db.habitDao().pausedHabitIds().size } == 2 }

        compose.onNodeWithContentDescription("Resume all habits").performClick()
        compose.waitUntil(TIMEOUT) { runBlocking { db.habitDao().pausedHabitIds().isEmpty() } }
        assertNull(runBlocking { db.habitDao().pausedHabitIds().firstOrNull() })
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
