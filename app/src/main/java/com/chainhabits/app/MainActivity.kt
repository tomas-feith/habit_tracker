package com.chainhabits.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.chainhabits.app.ui.HabitNavHost
import com.chainhabits.app.ui.theme.HabitTrackerTheme

class MainActivity : ComponentActivity() {
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationsIfNeeded()

        setContent {
            HabitTrackerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HabitNavHost()
                }
            }
        }
    }

    /**
     * Asks for notification permission once, on the first launch that needs it.
     *
     * Asking on every launch achieved nothing: Android stops showing the dialog after two
     * refusals and auto-denies silently, so the repeat attempts only ever fired a
     * pointless activity result. Remembering that we asked makes that explicit instead of
     * relying on the platform to absorb it.
     *
     * The permission is only a gate on reminders, which not every habit has, so nothing
     * here blocks or nags. A refusal leaves reminders inert until it is granted in system
     * settings - worth surfacing where a reminder time is actually set, rather than here.
     */
    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }

        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val PREFS = "chainhabits"
        const val KEY_ASKED_NOTIFICATIONS = "askedNotifications"
    }
}
