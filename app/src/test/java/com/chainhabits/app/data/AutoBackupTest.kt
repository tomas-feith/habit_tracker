package com.chainhabits.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AutoBackupTest {
    private fun name(
        day: Int,
        hour: Int = 3,
        minute: Int = 0,
        second: Int = 0,
    ) = scheduledBackupFileName(LocalDateTime.of(2026, 8, day, hour, minute, second))

    @Test
    fun `names carry the time to the second so two runs in a day cannot collide`() {
        val morning = scheduledBackupFileName(LocalDateTime.of(2026, 8, 22, 3, 0, 0))
        val evening = scheduledBackupFileName(LocalDateTime.of(2026, 8, 22, 21, 30, 15))

        assertEquals("habits-backup-2026-08-22-030000.json", morning)
        assertEquals("habits-backup-2026-08-22-213015.json", evening)
    }

    @Test
    fun `the name format sorts lexicographically in time order`() {
        // The retention rule sorts by name rather than parsing dates, so this is the
        // property it rests on.
        val names = listOf(name(9), name(10), name(22), name(1))
        assertEquals(listOf(name(1), name(9), name(10), name(22)), names.sorted())
    }

    @Test
    fun `keeps the newest and prunes the rest`() {
        val names = (1..20).map { name(it) }
        val pruned = backupsToPrune(names, keep = 14)

        assertEquals(6, pruned.size)
        // Days 1..6 are the oldest six.
        assertEquals((1..6).map { name(it) }.toSet(), pruned.toSet())
    }

    @Test
    fun `prunes nothing while under the limit`() {
        val names = (1..5).map { name(it) }
        assertEquals(emptyList<String>(), backupsToPrune(names, keep = 14))
    }

    @Test
    fun `never proposes deleting a file this app did not write`() {
        // The folder is the user's, not ours. Anything unrecognised is invisible to
        // retention rather than treated as an old backup.
        val strangers =
            listOf(
                "taxes.pdf",
                "habits-2026-08-22.json", // a manual export, different prefix
                "habits-backup-notadate.json",
                "habits-backup-2026-08-22-030000.json.bak",
                "HABITS-BACKUP-2026-08-22-030000.json",
                "",
            )
        val ours = (1..20).map { name(it) }

        val pruned = backupsToPrune(strangers + ours, keep = 0)

        assertEquals(ours.toSet(), pruned.toSet())
        assertTrue(strangers.none { it in pruned })
    }

    @Test
    fun `recognises its own names and nothing else`() {
        assertTrue(isScheduledBackupFileName(name(22)))
        assertFalse(isScheduledBackupFileName("habits-2026-08-22.json"))
        assertFalse(isScheduledBackupFileName("habits-backup-2026-08-22.json"))
        assertFalse(isScheduledBackupFileName("holiday.jpg"))
    }

    @Test
    fun `a keep of zero prunes every backup, and a negative keep is treated as zero`() {
        val names = (1..3).map { name(it) }
        assertEquals(names.size, backupsToPrune(names, keep = 0).size)
        // Guards against `drop(-1)`, which throws rather than dropping nothing.
        assertEquals(names.size, backupsToPrune(names, keep = -5).size)
    }

    @Test
    fun `shows a stored instant in the local calendar, without the sub-second noise`() {
        // Stored UTC because that is unambiguous and sorts; shown local because "05:53Z" is
        // not an answer to "when did this last work" for someone whose clock says 08:22.
        assertEquals(
            // Lisbon is UTC+1 in August.
            "23 Aug 2026, 07:53",
            describeBackupTime("2026-08-23T06:53:23.726263Z", ZoneId.of("Europe/Lisbon")),
        )
        assertEquals(
            "23 Aug 2026, 06:53",
            describeBackupTime("2026-08-23T06:53:23.726263Z", ZoneId.of("UTC")),
        )
    }

    @Test
    fun `returns an unparseable timestamp unchanged rather than hiding it`() {
        assertEquals("not a time", describeBackupTime("not a time", ZoneId.of("UTC")))
    }

    @Test
    fun `never prunes a backup saved by hand with the export button`() {
        // The manual export's own name, from the function that actually produces it.
        val manual = backupFileName(LocalDate.of(2026, 8, 1))

        assertFalse(isScheduledBackupFileName(manual))
        assertEquals(emptyList<String>(), backupsToPrune(listOf(manual), keep = 0))
    }

    @Test
    fun `handles an empty folder`() {
        assertEquals(emptyList<String>(), backupsToPrune(emptyList()))
    }
}
