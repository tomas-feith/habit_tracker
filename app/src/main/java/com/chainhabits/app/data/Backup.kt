package com.chainhabits.app.data

import com.chainhabits.app.domain.Polarity
import com.chainhabits.app.domain.Strictness
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Identifies a payload as ours; anything else is refused rather than half-read. */
const val BACKUP_FORMAT = "chainhabits-backup"

/** The highest payload version this build understands. */
const val BACKUP_VERSION = 1

/**
 * A backup of everything the app knows.
 *
 * This exists because nothing else can rebuild it. There is no server, and Android's own
 * backup is opportunistic: it runs on Google's schedule, cannot be triggered, and cannot be
 * verified. The one time it was needed it restored the history incompletely.
 *
 * Habits, entries and pauses are the whole of the stored state. Streaks, chain lengths and
 * the mosaic are all derived by [com.chainhabits.app.domain.HabitEvaluator] at read time,
 * so storing them would be storing a cached answer that could disagree with its inputs.
 *
 * Entities are serialised rather than domain types: they are the storage truth, they map to
 * columns one to one, and a restore has to reproduce rows rather than reconstruct meaning.
 */
private val backupJson =
    Json {
        prettyPrint = true

        // Written even where a value equals its declared default, so the file states what it
        // means rather than relying on the reader defaulting the same way. Without this an
        // entry with count 0 or a habit with sortOrder 0 loses the field entirely.
        encodeDefaults = true

        // A field added by a later writer must not stop an older reader; `version` already
        // gates the breaking changes.
        ignoreUnknownKeys = true

        isLenient = false
    }

@Serializable
private data class BackupEnvelope(
    val format: String? = null,
    val version: Int = 0,
    val exportedAt: String = "",
    val habits: List<HabitPayload> = emptyList(),
    val entries: List<EntryPayload> = emptyList(),
    val pauses: List<PausePayload> = emptyList(),
)

@Serializable
private data class HabitPayload(
    val id: Long,
    val name: String,
    val note: String? = null,
    val polarity: String,
    val strictness: String,
    val cadenceType: String,
    val cadenceDays: Int = 0,
    val cadenceTarget: Int = 1,
    val reminderMinuteOfDay: Int? = null,
    val createdOn: String,
    val archivedOn: String? = null,
    val sortOrder: Int = 0,
)

@Serializable
private data class EntryPayload(
    val habitId: Long,
    val date: String,
    val count: Int,
)

@Serializable
private data class PausePayload(
    val id: Long,
    val habitId: Long,
    val start: String,
    val end: String? = null,
)

/** The outcome of reading a backup file. */
sealed interface RestoreResult {
    data class Success(
        val habits: List<HabitEntity>,
        val entries: List<EntryEntity>,
        val pauses: List<PauseEntity>,
    ) : RestoreResult

    /** [reason] is written to be shown to the user as-is. */
    data class Failure(
        val reason: String,
    ) : RestoreResult
}

fun buildBackup(
    habits: List<HabitEntity>,
    entries: List<EntryEntity>,
    pauses: List<PauseEntity>,
    exportedAt: String,
): String =
    backupJson.encodeToString(
        BackupEnvelope(
            format = BACKUP_FORMAT,
            version = BACKUP_VERSION,
            exportedAt = exportedAt,
            habits = habits.map { it.toPayload() },
            entries = entries.map { EntryPayload(it.habitId, it.date.toString(), it.count) },
            pauses =
                pauses.map {
                    PausePayload(it.id, it.habitId, it.start.toString(), it.end?.toString())
                },
        ),
    )

/** A filename that sorts chronologically. */
fun backupFileName(today: LocalDate): String = "habits-$today.json"

/**
 * Parse a backup.
 *
 * Every failure returns a [RestoreResult.Failure] rather than throwing: all of them are
 * reachable by picking the wrong file from the document picker, and none is a programming
 * error.
 */
@Suppress("ReturnCount")
fun parseBackup(text: String): RestoreResult {
    val payload =
        runCatching { backupJson.decodeFromString<BackupEnvelope>(text) }.getOrNull()
            ?: return RestoreResult.Failure("That file is not readable as Habits data.")

    if (payload.format != BACKUP_FORMAT) {
        return RestoreResult.Failure("That file is not a Habits backup.")
    }

    if (payload.version > BACKUP_VERSION) {
        return RestoreResult.Failure(
            "That backup was written by a newer version of the app " +
                "(format ${payload.version}).",
        )
    }

    if (payload.habits.isEmpty()) {
        return RestoreResult.Failure("That backup contains no habits.")
    }

    val habits =
        runCatching { payload.habits.map { it.toEntity() } }.getOrNull()
            ?: return RestoreResult.Failure("That backup has a habit this version cannot read.")

    // Entries and pauses belonging to no habit are dropped rather than failing the restore.
    // A foreign key would reject them anyway, and losing an orphan row is better than
    // losing the whole history to one bad reference.
    val ids = habits.map { it.id }.toSet()

    val entries =
        runCatching {
            payload.entries
                .filter { it.habitId in ids }
                .map { EntryEntity(it.habitId, LocalDate.parse(it.date), it.count) }
        }.getOrNull()
            ?: return RestoreResult.Failure("That backup has an entry this version cannot read.")

    val pauses =
        runCatching {
            payload.pauses
                .filter { it.habitId in ids }
                .map {
                    PauseEntity(
                        it.id,
                        it.habitId,
                        LocalDate.parse(it.start),
                        it.end?.let(LocalDate::parse),
                    )
                }
        }.getOrNull()
            ?: return RestoreResult.Failure("That backup has a pause this version cannot read.")

    return RestoreResult.Success(habits, entries, pauses)
}

private fun HabitEntity.toPayload(): HabitPayload =
    HabitPayload(
        id = id,
        name = name,
        note = note,
        polarity = polarity.name,
        strictness = strictness.name,
        cadenceType = cadenceType.name,
        cadenceDays = cadenceDays,
        cadenceTarget = cadenceTarget,
        reminderMinuteOfDay = reminderMinuteOfDay,
        createdOn = createdOn.toString(),
        archivedOn = archivedOn?.toString(),
        sortOrder = sortOrder,
    )

private fun HabitPayload.toEntity(): HabitEntity =
    HabitEntity(
        id = id,
        name = name,
        note = note,
        polarity = Polarity.valueOf(polarity),
        strictness = Strictness.valueOf(strictness),
        cadenceType = CadenceType.valueOf(cadenceType),
        cadenceDays = cadenceDays,
        cadenceTarget = cadenceTarget,
        reminderMinuteOfDay = reminderMinuteOfDay,
        createdOn = LocalDate.parse(createdOn),
        archivedOn = archivedOn?.let(LocalDate::parse),
        sortOrder = sortOrder,
    )
