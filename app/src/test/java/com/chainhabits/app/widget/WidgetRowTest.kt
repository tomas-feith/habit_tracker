package com.chainhabits.app.widget

import com.chainhabits.app.domain.CellState
import com.chainhabits.app.domain.Polarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the widget says, which is the only part of it a unit test can reach - the Glance
 * composition itself renders in the launcher's process.
 */
class WidgetRowTest {
    private fun row(
        state: CellState = CellState.PENDING,
        count: Int = 0,
        target: Int? = null,
        headline: Int = 0,
        isWeekly: Boolean = false,
        isPaused: Boolean = false,
        pauseReason: String? = null,
        atRisk: Boolean = false,
    ) = WidgetRow(
        id = 1,
        name = "Read",
        state = state,
        count = count,
        target = target,
        headline = headline,
        isWeekly = isWeekly,
        polarity = Polarity.POSITIVE,
        isPaused = isPaused,
        pauseReason = pauseReason,
        atRisk = atRisk,
    )

    @Test
    fun `an undone habit one miss from broken says so on the launcher`() {
        val row = row(headline = 12, atRisk = true)
        assertTrue(row.isAtRiskNow)
        assertEquals("12 days at risk", row.subtitle)
    }

    @Test
    fun `the warning goes away the moment the habit is done`() {
        val row = row(count = 1, headline = 12, atRisk = true)

        // The warning must not outlive the thing that resolved it, or it teaches the user
        // to stop reading it.
        assertFalse(row.isAtRiskNow)
        assertEquals("12 days", row.subtitle)
    }

    @Test
    fun `a paused habit says why, when the user said`() {
        val row = row(isPaused = true, pauseReason = "Injured leg")

        // The launcher is where "paused" is read most and explained least.
        assertEquals("paused - Injured leg", row.subtitle)
    }

    @Test
    fun `a paused habit is not at risk, however its last period settled`() {
        val row = row(headline = 12, isPaused = true, atRisk = true)
        assertFalse(row.isAtRiskNow)
        assertEquals("paused", row.subtitle)
    }

    @Test
    fun `a weekly habit at risk keeps the quota, which is the actionable half`() {
        val row = row(count = 1, target = 3, headline = 6, isWeekly = true, atRisk = true)
        assertTrue(row.isAtRiskNow)
        assertEquals("1 of 3, chain at risk", row.subtitle)
    }

    @Test
    fun `a weekly habit at risk counts in weeks, not days`() {
        val row = row(target = 1, headline = 6, isWeekly = true, atRisk = true)
        assertEquals("6 weeks at risk", row.subtitle)
    }

    @Test
    fun `one day reads as a day`() {
        assertEquals("1 day at risk", row(headline = 1, atRisk = true).subtitle)
    }

    @Test
    fun `an ordinary undone habit is unchanged`() {
        assertEquals("9 days", row(headline = 9).subtitle)
        assertEquals("no chain yet", row(headline = 0).subtitle)
    }
}
