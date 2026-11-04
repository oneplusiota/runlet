package dev.runlet.app

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dev.runlet.app.ui.HostActions
import dev.runlet.app.ui.RunletApp
import dev.runlet.core.engine.RoutineBundle
import dev.runlet.core.platform.RunnerService
import dev.runlet.core.platform.RunletServices

class MainActivity : ComponentActivity(), HostActions {
    private val vm: MainViewModel by viewModels {
        val prefs = getSharedPreferences("ui", Context.MODE_PRIVATE)
        MainViewModel.factory(RunletServices.get(), object : UiPrefs {
            override var advancedEditor: Boolean
                get() = prefs.getBoolean(KEY_ADVANCED, false)
                set(v) = prefs.edit().putBoolean(KEY_ADVANCED, v).apply()
            override var seeded: Boolean
                get() = prefs.getBoolean(KEY_SEEDED, false)
                set(v) = prefs.edit().putBoolean(KEY_SEEDED, v).apply()
        })
    }

    private var afterNotificationPermission: (() -> Unit)? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val next = afterNotificationPermission
            afterNotificationPermission = null
            if (granted) next?.invoke()
            else vm.report("Allow notifications to run routines, then tap Run again.")
        }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { vm.importFrom(contentResolver, it) }
        }

    // A generic type, so the picker keeps the ".routine" extension instead of appending ".txt" as text/plain does.
    private val exportPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            uri?.let { vm.exportTo(contentResolver, it) }
        }

    private val backupPicker =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { vm.exportTo(contentResolver, it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RunletApp(vm, this) }
        // Only on a fresh launch: after a rotation the same intent is delivered again and must not import twice.
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /**
     * Opening a `.routine` file, or sharing a file or text to Runlet, imports it as a draft. Nothing runs.
     * The intent can come from any app, so it is only ever read through the bounded, validated import path.
     */
    private fun handleIncoming(incoming: Intent?) {
        when (incoming?.action) {
            Intent.ACTION_VIEW -> incoming.data?.let { vm.importFrom(contentResolver, it) }
            Intent.ACTION_SEND -> {
                val stream = if (Build.VERSION.SDK_INT >= 33) {
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (stream != null) vm.importFrom(contentResolver, stream)
                else vm.importText(incoming.getStringExtra(Intent.EXTRA_TEXT))
            }
            else -> return
        }
        setIntent(Intent())   // consumed
    }

    override fun onPause() {
        vm.flush()
        super.onPause()
    }

    override fun ensureCanRun(onReady: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterNotificationPermission = onReady
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            vm.report("Notifications are disabled. Enable them in app settings before starting.")
            return
        }
        onReady()
    }

    override fun startRun(automationId: Long) {
        try {
            RunnerService.run(this, automationId)
        } catch (e: Exception) {
            vm.report("Could not start: ${e.message}")
        }
    }

    override fun pickImport() = importPicker.launch(arrayOf("*/*"))

    override fun pickExport(suggestedName: String) = exportPicker.launch(RoutineBundle.fileNameFor(suggestedName))

    override fun pickBackup(suggestedFileName: String) = backupPicker.launch(suggestedFileName)

    override fun share(name: String, source: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, name)
            .putExtra(Intent.EXTRA_TEXT, source)
        startActivity(Intent.createChooser(send, "Share “$name”"))
    }

    override fun copyText(label: String, text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and later confirm a copy themselves; earlier versions say nothing.
        if (Build.VERSION.SDK_INT < 33) vm.report("Copied “$label”")
    }

    override fun clipboardText(): String? =
        getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun canScheduleExactAlarms(): Boolean = RunletServices.get().triggers.canScheduleExact()

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    private companion object {
        const val KEY_ADVANCED = "advanced_editor"
        const val KEY_SEEDED = "seeded"
    }
}
