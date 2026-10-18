package com.chainhabits.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chainhabits.app.data.BACKUPS_KEPT
import com.chainhabits.app.data.BackupFolder
import com.chainhabits.app.data.BackupSettings
import com.chainhabits.app.data.ScheduledBackup
import com.chainhabits.app.data.describeBackupTime
import com.chainhabits.app.notify.BackupWorker
import kotlinx.coroutines.launch

/**
 * Scheduled backups into a folder the user picks.
 *
 * The pitch on screen is concrete about what this is *for*, because Android already backs
 * the database up to the user's Google account and a second backup that does not explain
 * how it differs just reads as duplication. It differs in the way that matters day to day:
 * these are dated files that can be restored one at a time, so a mistake made a week ago is
 * recoverable.
 */
@Composable
fun BackupSection(
    settings: BackupSettings,
    folders: BackupFolder,
    backup: ScheduledBackup,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folder by settings.folder.collectAsStateWithLifecycle(initialValue = null)
    val lastBackupAt by settings.lastBackupAt.collectAsStateWithLifecycle(initialValue = null)
    val lastError by settings.lastError.collectAsStateWithLifecycle(initialValue = null)

    // Resolved from the provider rather than parsed out of the URI; see BackupFolder.
    var folderName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(folder) { folderName = folder?.let { folders.displayName(it.toUri()) } }

    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun backUpNow() =
        scope.launch {
            busy = true
            catching { backup.run() }
                .onSuccess {
                    failed = false
                    message = describe(it)
                }.onFailure {
                    failed = true
                    message = it.message ?: "Backup failed."
                }
            busy = false
        }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
            if (tree == null) return@rememberLauncherForActivityResult
            scope.launch {
                // Without taking the grant persistably it dies with the process, and the
                // first scheduled run tomorrow would fail on a folder the user was told
                // was configured.
                val taken =
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            tree,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }.isSuccess

                if (taken) {
                    settings.setFolder(tree.toString())
                    BackupWorker.schedule(context)
                    // A first backup straight away, so the folder is proven to work while
                    // the user is still looking rather than tomorrow when nobody is.
                    backUpNow()
                } else {
                    failed = true
                    message = "Android would not grant lasting access to that folder."
                }
            }
        }

    Text(
        "Scheduled backups",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "Saves a dated backup to a folder you choose, once a day, keeping the last " +
            "$BACKUPS_KEPT. Point it at a folder your cloud app already syncs and the " +
            "copies leave the phone without this app needing an account or internet access.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    BackupStatus(folder, folderName, lastBackupAt, lastError)

    OutlinedButton(
        onClick = { picker.launch(null) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (folder == null) "Choose backup folder" else "Choose a different folder")
    }

    if (folder != null) {
        OutlinedButton(
            onClick = { backUpNow() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back up now")
        }

        OutlinedButton(
            onClick = {
                scope.launch {
                    BackupWorker.cancel(context)
                    settings.clearFolder()
                    failed = false
                    message = "Scheduled backups turned off. Existing files are left alone."
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Turn off scheduled backups")
        }
    }

    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
    }
}

private fun describe(outcome: ScheduledBackup.Outcome): String =
    when (outcome) {
        is ScheduledBackup.Outcome.Saved -> "Saved ${outcome.name}."
        ScheduledBackup.Outcome.NoFolder -> "No backup folder chosen yet."
        ScheduledBackup.Outcome.NothingToSave -> "There are no habits to back up yet."
    }

/** Where the backups go, when the last one ran, and whether it is still working. */
@Composable
private fun BackupStatus(
    folder: String?,
    folderName: String?,
    lastBackupAt: String?,
    lastError: String?,
) {
    if (folder != null) {
        Text(
            // The name can genuinely be unavailable - a provider that does not report one,
            // or a folder that has since gone. Saying so beats printing the raw URI, which
            // for Google Drive is a base64 blob and tells the user nothing at all.
            folderName?.let { "Saving to $it" } ?: "Saving to the folder you chose.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            lastBackupAt?.let { "Last backup ${describeBackupTime(it)}" } ?: "No backup taken yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // The worker's own complaint, which outlives this screen. Shown separately from the
    // section's message, which only reports what the user just did.
    lastError?.let {
        Text(
            "The last scheduled backup failed: $it Choose the folder again to fix it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
