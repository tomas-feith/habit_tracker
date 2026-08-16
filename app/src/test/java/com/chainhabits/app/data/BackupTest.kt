package com.chainhabits.app.data

import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The backup format.
 *
 * The round trip is the test that matters: this file is the only copy of a history that
 * cannot be reconstructed from anywhere, so a backup this app writes and cannot read again
 * would be a backup that silently is not one.
 */
class BackupTest {
    private val exportedAt = "2026-08-15T20:30:00Z"

    private val habits =
        listOf(
            HabitEntity(
                id = 1,
                name = "Read 20 pages",
                note = "Fiction counts.",
                polarity = Polarity.POSITIVE,
                strictness = Strictness.STANDARD,
                cadenceType = CadenceType.DAILY,
                cadenceDays = 0,
                cadenceTarget = 1,
                reminderMinuteOfDay = 21 * 60 + 30,
                createdOn = LocalDate.parse("2026-01-01"),
                archivedOn = null,
                sortOrder = 0,
            ),
            HabitEntity(
                id = 2,
                name = "Gym Mon/Wed/Fri",
                note = null,
                polarity = Polarity.POSITIVE,
                strictness = Strictness.STRICT,
                cadenceType = CadenceType.SPECIFIC_DAYS,
                // Mon, Wed, Fri
                cadenceDays = (1 shl 1) or (1 shl 3) or (1 shl 5),
                cadenceTarget = 1,
                reminderMinuteOfDay = null,
                createdOn = LocalDate.parse("2025-06-15"),
                // An archived habit: still history, and must survive the round trip.
                archivedOn = LocalDate.parse("2026-05-01"),
                sortOrder = 3,
            ),
            HabitEntity(
                id = 3,
                name = "No McDonald's",
                note = null,
                polarity = Polarity.NEGATIVE,
                strictness = Strictness.STANDARD,
                cadenceType = CadenceType.TIMES_PER_WEEK,
                cadenceDays = 0,
                cadenceTarget = 2,
                reminderMinuteOfDay = null,
                createdOn = LocalDate.parse("2026-02-02"),
                archivedOn = null,
                sortOrder = 1,
            ),
        )

    private val entries =
        listOf(
            EntryEntity(1, LocalDate.parse("2026-08-01"), 1),
            EntryEntity(1, LocalDate.parse("2026-08-02"), 3),
            // A zero count is a real value, not an absence.
            EntryEntity(3, LocalDate.parse("2026-08-02"), 0),
        )

    private val pauses =
        listOf(
            PauseEntity(1, 1, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-14")),
            // An open pause: still running, end is null.
            PauseEntity(2, 2, LocalDate.parse("2026-08-10"), null),
        )

    private fun roundTrip(): RestoreResult.Success =
        parseBackup(buildBackup(habits, entries, pauses, exportedAt)) as RestoreResult.Success

    @Test
    fun `round trips every habit, entry and pause`() {
        val restored = roundTrip()
        assertEquals(habits, restored.habits)
        assertEquals(entries, restored.entries)
        assertEquals(pauses, restored.pauses)
    }

    @Test
    fun `keeps archived habits`() {
        // An export that quietly dropped them would restore a library missing everything
        // the user had finished with.
        assertEquals(
            LocalDate.parse("2026-05-01"),
            roundTrip().habits.single { it.id == 2L }.archivedOn,
        )
    }

    @Test
    fun `preserves every cadence shape`() {
        val byId = roundTrip().habits.associateBy { it.id }
        assertEquals(CadenceType.DAILY, byId.getValue(1).cadenceType)
        assertEquals(CadenceType.SPECIFIC_DAYS, byId.getValue(2).cadenceType)
        assertEquals((1 shl 1) or (1 shl 3) or (1 shl 5), byId.getValue(2).cadenceDays)
        assertEquals(CadenceType.TIMES_PER_WEEK, byId.getValue(3).cadenceType)
        assertEquals(2, byId.getValue(3).cadenceTarget)
    }

    @Test
    fun `preserves polarity and strictness`() {
        val byId = roundTrip().habits.associateBy { it.id }
        assertEquals(Polarity.NEGATIVE, byId.getValue(3).polarity)
        assertEquals(Strictness.STRICT, byId.getValue(2).strictness)
    }

    @Test
    fun `preserves a zero count and a null reminder`() {
        // encodeDefaults is on precisely so these survive; without it both fields vanish.
        assertEquals(0, roundTrip().entries.single { it.habitId == 3L }.count)
        assertEquals(null, roundTrip().habits.single { it.id == 2L }.reminderMinuteOfDay)
        assertEquals(21 * 60 + 30, roundTrip().habits.single { it.id == 1L }.reminderMinuteOfDay)
    }

    @Test
    fun `preserves an open pause as still running`() {
        assertEquals(null, roundTrip().pauses.single { it.id == 2L }.end)
    }

    @Test
    fun `stamps the format and version the reader checks`() {
        val text = buildBackup(habits, entries, pauses, exportedAt)
        assertTrue(text.contains("\"format\": \"$BACKUP_FORMAT\""))
        assertTrue(text.contains("\"version\": $BACKUP_VERSION"))
    }

    @Test
    fun `refuses a file that is not a backup`() {
        assertTrue(
            parseBackup("""{"format":"something-else","version":1}""") is RestoreResult.Failure,
        )
        assertTrue(parseBackup("not json") is RestoreResult.Failure)
        assertTrue(parseBackup("") is RestoreResult.Failure)
    }

    @Test
    fun `refuses a payload from a newer writer instead of dropping fields`() {
        val newer =
            buildBackup(
                habits,
                entries,
                pauses,
                exportedAt,
            ).replace("\"version\": 1", "\"version\": 2")
        val result = parseBackup(newer)
        assertTrue(result is RestoreResult.Failure)
        assertTrue((result as RestoreResult.Failure).reason.contains("newer"))
    }

    @Test
    fun `refuses a backup with no habits`() {
        assertTrue(
            parseBackup(
                buildBackup(emptyList(), emptyList(), emptyList(), exportedAt),
            ) is RestoreResult.Failure,
        )
    }

    @Test
    fun `drops rows pointing at a habit the backup does not contain`() {
        // A foreign key would reject them anyway, and losing an orphan beats losing the
        // whole history to one bad reference.
        val text =
            buildBackup(
                habits.take(1),
                entries + EntryEntity(999, LocalDate.parse("2026-08-03"), 1),
                pauses + PauseEntity(9, 999, LocalDate.parse("2026-08-01"), null),
                exportedAt,
            )
        val restored = parseBackup(text) as RestoreResult.Success
        assertTrue(restored.entries.none { it.habitId == 999L })
        assertTrue(restored.pauses.none { it.habitId == 999L })
        // The rows that do belong to habit 1 are still there.
        assertEquals(2, restored.entries.count { it.habitId == 1L })
    }

    // A file that decodes cleanly can still be impossible to restore. These come from
    // hand-editing or concatenating backups, never from buildBackup - and before they were
    // checked, the first two threw out of the restore instead of reporting a failure, and
    // the third restored silently with one of the two counts picked arbitrarily.

    private fun reasonFor(
        habits: List<HabitEntity> = this.habits,
        entries: List<EntryEntity> = this.entries,
        pauses: List<PauseEntity> = this.pauses,
    ): String {
        val result = parseBackup(buildBackup(habits, entries, pauses, exportedAt))
        assertTrue("expected a failure, got $result", result is RestoreResult.Failure)
        return (result as RestoreResult.Failure).reason
    }

    @Test
    fun `refuses two habits sharing an id`() {
        // insertHabit uses ABORT, so this used to surface as a raw SQL exception.
        assertTrue(reasonFor(habits = habits + habits.first()).contains("twice"))
    }

    @Test
    fun `refuses two pauses sharing an id`() {
        assertTrue(reasonFor(pauses = pauses + pauses.first()).contains("twice"))
    }

    @Test
    fun `refuses the same day logged twice for one habit`() {
        // entries uses REPLACE, so this would have restored quietly with a coin-flip count.
        val clash = EntryEntity(1, LocalDate.parse("2026-08-01"), 9)
        assertTrue(reasonFor(entries = entries + clash).contains("2026-08-01"))
    }

    @Test
    fun `the same date under different habits is not a clash`() {
        val sameDayOtherHabit = EntryEntity(3, LocalDate.parse("2026-08-01"), 1)
        val result =
            parseBackup(buildBackup(habits, entries + sameDayOtherHabit, pauses, exportedAt))
        assertTrue(result is RestoreResult.Success)
    }

    @Test
    fun `refuses a negative count`() {
        // The evaluator sums counts, so a negative would subtract from a real day.
        val negative = EntryEntity(1, LocalDate.parse("2026-08-05"), -1)
        assertTrue(reasonFor(entries = entries + negative).contains("negative"))
    }

    @Test
    fun `still accepts a zero count`() {
        // Zero is a real stored value, not corruption; only negatives are refused.
        assertTrue(
            parseBackup(buildBackup(habits, entries, pauses, exportedAt)) is RestoreResult.Success,
        )
    }

    @Test
    fun `a duplicate belonging to a dropped habit does not fail the restore`() {
        // Orphans are filtered before validation, so junk pointing at a habit that is not
        // in the file must not block a restore that is otherwise sound.
        val orphanClash =
            listOf(
                EntryEntity(999, LocalDate.parse("2026-08-03"), 1),
                EntryEntity(999, LocalDate.parse("2026-08-03"), 2),
            )
        val result =
            parseBackup(buildBackup(habits.take(1), entries + orphanClash, pauses, exportedAt))
        assertTrue(result is RestoreResult.Success)
    }

    @Test
    fun `carries non-ASCII habit names through unchanged`() {
        val accented = habits.first().copy(name = "Ler 20 páginas")
        val restored =
            parseBackup(buildBackup(listOf(accented), emptyList(), emptyList(), exportedAt))
                as RestoreResult.Success
        assertEquals("Ler 20 páginas", restored.habits.single().name)
    }
}
