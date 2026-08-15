package com.chainhabits.app.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the note length cap against the UTF-16 boundary case.
 *
 * The cap exists so a paste cannot push the detail screen's layout apart, but a naive
 * `take` counts code units rather than characters. A note ending in an emoji is entirely
 * ordinary ("no takeaway this week"), so the cut landing between the two halves of a
 * surrogate pair is a real input, not a theoretical one - and the damaged string would be
 * persisted, not just mis-drawn once.
 */
class NoteTruncationTest {
    /** U+1F35F, two UTF-16 code units. */
    private val fries = "🍟"

    @Test
    fun shortNoteIsUntouched() {
        assertEquals("read before bed", "read before bed".takeChars(MAX_NOTE_LENGTH))
    }

    @Test
    fun noteAtExactlyTheCapIsUntouched() {
        val exact = "a".repeat(MAX_NOTE_LENGTH)
        assertEquals(exact, exact.takeChars(MAX_NOTE_LENGTH))
    }

    @Test
    fun overlongNoteIsCutToTheCap() {
        val long = "a".repeat(MAX_NOTE_LENGTH + 50)
        assertEquals(MAX_NOTE_LENGTH, long.takeChars(MAX_NOTE_LENGTH).length)
    }

    /**
     * The boundary that matters: an emoji straddling the cut. Taking the full [max] would
     * keep the high surrogate and drop the low one.
     */
    @Test
    fun aSplitEmojiIsDroppedWholeRatherThanHalved() {
        // The emoji's two units sit at indices max-1 and max, so a plain take() splits it.
        val text = "a".repeat(MAX_NOTE_LENGTH - 1) + fries + "trailing"

        val cut = text.takeChars(MAX_NOTE_LENGTH)

        assertEquals(MAX_NOTE_LENGTH - 1, cut.length)
        assertFalse("a lone surrogate must never survive", cut.last().isHighSurrogate())
        assertTrue("the whole emoji should be gone", fries !in cut)
    }

    /** An emoji that fits entirely inside the cap must survive intact. */
    @Test
    fun anEmojiWhollyInsideTheCapSurvives() {
        val text = "a".repeat(MAX_NOTE_LENGTH - 2) + fries + "trailing"

        val cut = text.takeChars(MAX_NOTE_LENGTH)

        assertEquals(MAX_NOTE_LENGTH, cut.length)
        assertTrue("the emoji fits and must be kept", cut.endsWith(fries))
    }
}
