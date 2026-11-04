package dev.runlet.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.core.model.Action

/** How one kind of step looks wherever it is shown: its icon, colour, headline and detail line. */
@Immutable
data class StepStyle(val icon: ImageVector, val tint: Color, val title: String, val detail: String)

@Composable
fun Action.style(): StepStyle {
    val x = Runlet.extras
    return when (this) {
        is Action.Stage -> StepStyle(RunletIcons.Flag, x.stage, label, "Stage · ${formatDuration(durationMs)}")
        is Action.Wait -> StepStyle(RunletIcons.Hourglass, x.wait, "Wait", "Silent pause · ${formatDuration(durationMs)}")
        Action.Chime -> StepStyle(RunletIcons.MusicNote, x.sound, "Chime", "Plays a short sound")
        is Action.Vibrate -> StepStyle(RunletIcons.Vibrate, x.haptic, "Vibrate", formatDuration(durationMs))
        is Action.Notify -> StepStyle(RunletIcons.Notifications, x.message, "Notification", message)
        is Action.Volume -> StepStyle(RunletIcons.Volume, x.volume, "Media volume", "Set to $percent%")
        is Action.Log -> StepStyle(RunletIcons.Log, x.note, "Log entry", message)
    }
}

/**
 * The icon and colour for each action type, keyed by its registry id. Kept in the UI layer so the engine
 * stays free of Compose; a type with no entry here falls back to a neutral look instead of failing.
 */
@Composable
fun actionLook(id: String): Pair<ImageVector, Color> {
    val x = Runlet.extras
    return when (id) {
        "stage" -> RunletIcons.Flag to x.stage
        "wait" -> RunletIcons.Hourglass to x.wait
        "notify" -> RunletIcons.Notifications to x.message
        "chime" -> RunletIcons.MusicNote to x.sound
        "vibrate" -> RunletIcons.Vibrate to x.haptic
        "volume" -> RunletIcons.Volume to x.volume
        "log" -> RunletIcons.Log to x.note
        else -> RunletIcons.Flag to x.note
    }
}

/** Icon and tint for the two entries that are not [Action]s. */
@Composable
fun repeatTint(): Color = Runlet.extras.repeat

@Composable
fun noteTint(): Color = Runlet.extras.note
