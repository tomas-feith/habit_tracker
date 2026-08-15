package com.chainhabits.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chainhabits.app.data.HabitRepository
import com.chainhabits.app.data.RestoreResult
import com.chainhabits.app.data.backupFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * Read a document the user picked, as UTF-8.
 *
 * The charset is stated rather than left to the platform default, which on some systems is
 * cp1252 and would corrupt an accented habit name with no error anywhere.
 */
private suspend fun readText(
    context: Context,
    uri: Uri,
): String =
    withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not open that file." }
                .readBytes()
                .toString(Charsets.UTF_8)
        }
    }

private suspend fun writeText(
    context: Context,
    uri: Uri,
    text: String,
) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri).use { stream ->
            requireNotNull(stream) { "Could not write to that file." }
                .write(text.toByteArray(Charsets.UTF_8))
        }
    }
}

/**
 * Runs [block], turning any failure into a message. Cancellation is rethrown rather than
 * captured: it is how a scope stops its children, not a failure, and `runCatching` cannot
 * be used because it swallows it.
 */
private suspend fun <T> catching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        Result.failure(e)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: HabitRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                catching {
                    writeText(
                        context,
                        uri,
                        repository.exportBackup(Instant.now().toString()),
                    )
                }.onSuccess {
                    failed = false
                    message = "Backup saved."
                }.onFailure {
                    failed = true
                    message = it.message ?: "Export failed."
                }
                busy = false
            }
        }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            // Confirmed before anything is read: a restore replaces everything.
            if (uri != null) pendingRestore = uri
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Backup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Explanation()

            OutlinedButton(
                onClick = { exportLauncher.launch(backupFileName(LocalDate.now())) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save a backup")
            }

            OutlinedButton(
                // Not "application/json": some file managers report a .json file as
                // text/plain, and a strict filter hides the very file being looked for.
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore from a backup")
            }

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }

    pendingRestore?.let { uri ->
        RestoreDialog(
            onDismiss = { pendingRestore = null },
            onConfirm = {
                pendingRestore = null
                scope.launch {
                    busy = true
                    catching { repository.restoreBackup(readText(context, uri)) }
                        .onSuccess { result ->
                            failed = result is RestoreResult.Failure
                            message =
                                when (result) {
                                    is RestoreResult.Success -> {
                                        "Restored ${result.habits.size} habits and " +
                                            "${result.entries.size} logged days."
                                    }

                                    is RestoreResult.Failure -> {
                                        result.reason
                                    }
                                }
                        }.onFailure {
                            failed = true
                            message = it.message ?: "Restore failed."
                        }
                    busy = false
                }
            },
        )
    }
}

@Composable
private fun RestoreDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace everything?") },
        text = {
            Text(
                "Every habit and all of its history will be replaced by the contents of " +
                    "this file. This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Replace", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Explanation() {
    Text(
        "Your history",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        "A backup holds every habit, every logged day and every pause - including archived " +
            "habits. Nothing else can rebuild it: there is no server, and Android's own " +
            "backup runs on Google's schedule and cannot be checked.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Save it somewhere off the phone. Uninstalling the app deletes the database.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
