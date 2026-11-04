package dev.runlet.core.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import dev.runlet.core.engine.Clock
import dev.runlet.core.engine.Platform
import kotlin.math.roundToInt

/** Monotonic clock that keeps counting in deep sleep and ignores wall-clock changes. */
val ElapsedRealtimeClock = Clock { SystemClock.elapsedRealtime() }

object Channels {
    const val RUNNER = "runner"
    const val MESSAGES = "messages"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RUNNER, "Running routines", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Timer progress and pause/resume controls"
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(MESSAGES, "Script messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }
}

/** Real-device [Platform]: sound, vibration, notifications and media volume. */
class AndroidPlatform(
    context: Context,
    private val notificationIcon: Int,
    private val onLog: (String) -> Unit = {},
) : Platform {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun chime() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, TONE_MS)
            mainHandler.postDelayed({ tone.release() }, TONE_RELEASE_MS)
        } catch (e: RuntimeException) {
            onLog("Chime unavailable: ${e.message}")
        }
    }

    override fun vibrate(durationMs: Long) {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
        )
    }

    override fun notify(title: String, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        Channels.ensure(context)
        val notification = Notification.Builder(context, Channels.MESSAGES)
            .setSmallIcon(notificationIcon)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_ID, notification)
    }

    override fun setMediaVolume(percent: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100f).roundToInt(), 0)
    }

    override fun log(message: String) = onLog(message)

    companion object {
        const val ALERT_ID = 12
        private const val TONE_VOLUME = 45
        private const val TONE_MS = 250
        private const val TONE_RELEASE_MS = 400L
    }
}
