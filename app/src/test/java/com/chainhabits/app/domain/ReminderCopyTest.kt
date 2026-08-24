package com.chainhabits.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The wording of the nudge.
 *
 * Asserted on rather than eyeballed because the whole point of the copy is that it names a
 * real stake, and the one way it can go wrong is by naming a stake that is not real - see
 * [ReminderCopy] on why a standard habit must not be told its chain dies tonight.
 */
class ReminderCopyTest {
    private val start = LocalDate.of(2026, 1, 1)

    private fun habit(
        strictness: Strictness = Strictness.STANDARD,
        cadence: Cadence = Cadence.Daily,
    ) = Habit(
        id = 1,
        name = "Read",
        strictness = strictness,
        cadence = cadence,
        createdOn = start,
    )

    private fun stats(
        currentStreak: Int = 0,
        chainLength: Int = currentStreak,
        longestStreak: Int = currentStreak,
        previousBest: Int = longestStreak,
        atRisk: Boolean = false,
    ) = HabitStats(
        currentStreak = currentStreak,
        chainLength = chainLength,
        longestStreak = longestStreak,
        previousBest = previousBest,
        completionRate = 1f,
        atRisk = atRisk,
    )

    private fun body(
        habit: Habit,
        stats: HabitStats,
        currentCount: Int = 0,
    ) = ReminderCopy.forHabit(habit, stats, currentCount).body

    @Test
    fun `the habit name stays the title, so reminders are still told apart`() {
        val copy = ReminderCopy.forHabit(habit(), stats(currentStreak = 5), 0)
        assertEquals("Read", copy.title)
    }

    @Test
    fun `a chain on one miss is told exactly what a second would cost`() {
        val text = body(habit(), stats(currentStreak = 0, chainLength = 12, atRisk = true))
        assertEquals("You missed yesterday. Miss again and 12 days goes with it.", text)
    }

    @Test
    fun `a standard habit that is not at risk is never threatened with losing the chain`() {
        val text = body(habit(), stats(currentStreak = 30, chainLength = 30, longestStreak = 99))

        // The lie the copy must never tell: under never-miss-twice a single miss here is
        // amber and recoverable, so nothing "goes" and nothing "starts again".
        assertTrue(text, "goes with it" !in text)
        assertTrue(text, "starts the count again" !in text)
        assertEquals("Day 30. Still time to keep the chain.", text)
    }

    @Test
    fun `a strict habit is threatened, because for it the threat is true`() {
        val text = body(habit(strictness = Strictness.STRICT), stats(currentStreak = 47))
        assertEquals("47 days clean. One miss starts the count again.", text)
    }

    @Test
    fun `a habit with nothing built yet is invited rather than counted at zero`() {
        assertEquals("No chain yet. Today is day one.", body(habit(), stats()))
    }

    @Test
    fun `a run past its longest ever says so`() {
        val text =
            body(
                habit(),
                stats(currentStreak = 21, chainLength = 21, longestStreak = 21, previousBest = 14),
            )
        assertEquals("Day 21, your longest run yet. Still time today.", text)
    }

    @Test
    fun `merely equalling an old best is not called a record`() {
        val text =
            body(
                habit(),
                stats(currentStreak = 21, chainLength = 21, longestStreak = 21, previousBest = 21),
            )
        assertEquals("Day 21. Still time to keep the chain.", text)
    }

    @Test
    fun `one day reads as a day, not as 1 days`() {
        val text = body(habit(strictness = Strictness.STRICT), stats(currentStreak = 1))
        assertEquals("1 day clean. One miss starts the count again.", text)
    }

    @Test
    fun `a weekly habit leads with the quota, which is what is actually actionable`() {
        val text =
            body(
                habit(cadence = Cadence.TimesPerWeek(3)),
                stats(currentStreak = 4, chainLength = 4, longestStreak = 9),
                currentCount = 1,
            )
        assertEquals("1 of 3 this week, 2 to go. 4 weeks so far.", text)
    }

    @Test
    fun `a weekly habit on one miss names the weeks at stake`() {
        val text =
            body(
                habit(cadence = Cadence.TimesPerWeek(3)),
                stats(currentStreak = 0, chainLength = 6, atRisk = true),
                currentCount = 0,
            )
        assertEquals("0 of 3 this week, 3 to go. Fall short again and 6 weeks goes with it.", text)
    }

    @Test
    fun `a once-a-week habit does not offer a one-of-one quota`() {
        val text =
            body(
                habit(cadence = Cadence.TimesPerWeek(1)),
                stats(currentStreak = 2, chainLength = 2, longestStreak = 8),
            )
        assertEquals("Not done this week yet. 2 weeks so far.", text)
    }

    /**
     * The number in the reminder has to be the number on the screen behind it. Both come
     * from [headlineFor] now, and this is what keeps them there.
     */
    @Test
    fun `the quoted number is the chain for standard habits and the streak for strict`() {
        val stats = stats(currentStreak = 3, chainLength = 11, longestStreak = 40)
        assertTrue(body(habit(), stats).contains("Day 11"))
        assertTrue(
            body(habit(strictness = Strictness.STRICT), stats).contains("3 days clean"),
        )
    }
}
