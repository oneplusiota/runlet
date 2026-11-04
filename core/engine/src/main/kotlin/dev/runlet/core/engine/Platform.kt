package dev.runlet.core.engine

/** Things an automation may need from the device. Drives permission onboarding. */
enum class Capability(val label: String) {
    NOTIFICATIONS("Post notifications"),
    SOUND("Play sounds"),
    VIBRATION("Vibrate"),
    AUDIO_SETTINGS("Change media volume"),
}

/**
 * Port to the device. The engine never touches Android directly; `:core:platform`
 * provides the real implementation and tests provide a fake.
 */
interface Platform {
    fun chime()
    fun vibrate(durationMs: Long)
    fun notify(title: String, message: String)
    /** Set media volume as a 0–100 percentage of the device maximum. */
    fun setMediaVolume(percent: Int)
    /** Append a line to the run log. */
    fun log(message: String)
}

fun interface Clock {
    fun nowMs(): Long
}

/** Formats milliseconds as mm:ss, rounding up like the prototype's countdown. */
fun formatClock(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
