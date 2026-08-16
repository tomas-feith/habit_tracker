package com.chainhabits.app.ui.edit

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chainhabits.app.data.HabitDatabase
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.domain.Cadence
import com.chainhabits.app.domain.Habit
import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.ui.theme.HabitTrackerTheme
import kotlinx.coroutines.flow.first
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
 * The habit form, driven end to end against a real database.
 *
 * The interesting cases are the ones where the screen has to preserve something it does
 * not show. An edit form is the easiest place in an app to lose a field by omission - it
 * builds a whole object out of the controls on screen - and that is exactly the bug this
 * suite was written after.
 */
@RunWith(AndroidJUnit4::class)
class EditScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: HabitDatabase
    private lateinit var repository: HabitRepository

    private val today: LocalDate = LocalDate.now()
    private var done = false

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
    ): Long =
        runBlocking {
            repository.addHabit(
                Habit(name = name, cadence = cadence, createdOn = today.minusDays(30)),
            )
        }

    private fun show(habitId: Long) {
        compose.setContent {
            HabitTrackerTheme {
                EditScreen(
                    viewModel = EditViewModel(repository, habitId, SavedStateHandle()),
                    onDone = { done = true },
                )
            }
        }
    }

    private fun awaitText(text: String) =
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitDone() = compose.waitUntil(TIMEOUT) { done }

    private fun nameField() = compose.onAllNodes(hasSetTextAction())[0]

    private fun noteField() = compose.onAllNodes(hasSetTextAction())[1]

    private fun habits() = runBlocking { repository.observeHabits().first() }

    @Test
    fun aHabitCannotBeCreatedWithoutAName() {
        show(NEW)
        compose.onNodeWithText("Create habit").assertIsNotEnabled()

        nameField().performTextInput("Read")

        compose.onNodeWithText("Create habit").assertIsEnabled()
    }

    @Test
    fun creatingAHabitStoresWhatWasTyped() {
        show(NEW)
        nameField().performTextInput("Read 20 pages")
        noteField().performTextInput("Fiction counts.")

        compose.onNodeWithText("Create habit").performScrollTo().performClick()
        awaitDone()

        val stored = habits().single()
        assertEquals("Read 20 pages", stored.name)
        assertEquals("Fiction counts.", stored.note)
    }

    @Test
    fun editingAHabitKeepsItsPlaceInTheList() {
        // The regression, through the real screen: the form does not model sortOrder, and
        // updateHabit rewrites the whole row, so an edit used to send the habit to the top.
        val a = add("A")
        val b = add("B")
        runBlocking { repository.applyOrder(listOf(b, a)) }

        show(a)
        awaitText("Save")
        nameField().performTextInput("!")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        awaitDone()

        assertEquals(listOf("B", "!A"), habits().map { it.name })
    }

    @Test
    fun editingLoadsTheHabitRatherThanAnEmptyForm() {
        val id = add("Workout", cadence = Cadence.TimesPerWeek(4))
        show(id)

        awaitText("Workout")
        // The cadence and its target come back too, not just the name.
        compose.onNodeWithText("  4x per week  ").assertExists()
    }

    @Test
    fun aNoteIsCappedRatherThanRejected() {
        show(NEW)
        nameField().performTextInput("Read")
        noteField().performTextInput("x".repeat(MAX_NOTE_LENGTH + 40))

        compose.onNodeWithText("Create habit").performScrollTo().performClick()
        awaitDone()

        // Truncate rather than reject, so a paste that is slightly too long still lands.
        assertEquals(MAX_NOTE_LENGTH, habits().single().note?.length)
    }

    @Test
    fun theWeeklyTargetWillNotGoBelowOne() {
        show(NEW)
        nameField().performTextInput("Workout")
        compose.onNodeWithText("Weekly").performScrollTo().performClick()

        // Default is 3; two steps down reaches the floor.
        repeat(2) { compose.onNodeWithText("-").performClick() }

        compose.onNodeWithText("  1x per week  ").assertExists()
        // A target below one would mean the habit can never be satisfied.
        compose.onNodeWithText("-").assertIsNotEnabled()
    }

    @Test
    fun choosingSpecificDaysRevealsTheDayPicker() {
        show(NEW)
        compose.onNodeWithText("Days").performScrollTo().performClick()

        // The default selection is Mon/Wed/Fri, so the picker is on screen and populated.
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("How often").assertExists()
    }

    @Test
    fun aNewHabitOffersNoDeleteButton() {
        show(NEW)
        assertEquals(
            0,
            compose
                .onAllNodesWithText("Delete habit")
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun deletingRemovesTheHabitAndItsHistory() {
        val id = add("Read")
        runBlocking { repository.logEvent(repository.getHabit(id)!!, today) }

        show(id)
        awaitText("Read")
        compose.onNodeWithContentDescription("Delete habit").performClick()
        awaitDone()

        assertNull(runBlocking { repository.getHabit(id) })
        assertEquals(0, runBlocking { db.habitDao().allEntries().size })
    }

    @Test
    fun switchingToBreakChangesWhatAnUntouchedDayMeans() {
        show(NEW)
        nameField().performTextInput("No takeaway")
        compose.onNodeWithText("Break").performScrollTo().performClick()

        awaitText("You log slips. An untouched day stays clean.")
        compose.onNodeWithText("Create habit").performScrollTo().performClick()
        awaitDone()

        assertEquals(Polarity.NEGATIVE, habits().single().polarity)
    }

    private companion object {
        const val TIMEOUT = 5_000L

        /** Matches the sentinel HabitNavHost uses for "no habit yet". */
        const val NEW = -1L
    }
}
