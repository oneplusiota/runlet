package dev.runlet.app.ui.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.runlet.app.ui.theme.Runlet
import kotlinx.coroutines.delay
import kotlin.math.abs

/** "4 min 50 s", "1 h 5 min", "30 s", "1.5 s". Used wherever a length of time is described in words. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 s"
    if (ms < 1_000) return "$ms ms"
    if (ms < 60_000 && ms % 1_000 != 0L) return "%.1f s".format(ms / 1000.0)
    val totalSeconds = ms / 1_000
    val h = totalSeconds / 3_600
    val m = (totalSeconds % 3_600) / 60
    val s = totalSeconds % 60
    return buildList {
        if (h > 0) add("$h h")
        if (m > 0) add("$m min")
        if (s > 0 && h == 0L) add("$s s")
    }.joinToString(" ")
}

/** "04:50" or "1:05:00". Used for running countdowns, rounding up like a kitchen timer. */
fun clockText(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1_000
    val h = s / 3_600
    val m = (s % 3_600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun stepsLabel(n: Int): String = if (n == 1) "1 step" else "$n steps"

/** A rounded square holding [icon], tinted softly with [tint]. */
@Composable
fun IconTile(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** First letter of [name] on a tinted tile; the tint is stable per name so each routine keeps its colour. */
@Composable
fun MonogramTile(name: String, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val x = Runlet.extras
    val palette = listOf(x.stage, x.wait, x.sound, x.haptic, x.message, x.volume, x.repeat)
    val tint = palette[abs(name.hashCode()) % palette.size]
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.34f)).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "•",
            color = tint,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A small rounded label, optionally with a leading icon. */
@Composable
fun Pill(text: String, tint: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.semantics(mergeDescendants = true) {}
            .clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

/** Centered illustration-free empty state: an icon tile, a title, a sentence, and an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, tint, size = 72.dp, iconSize = 34.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Monotonic "now", refreshed ~20 times a second while [running]; drives smooth countdowns. */
@Composable
fun rememberNowMs(running: Boolean): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(running) {
        while (running) {
            now = SystemClock.elapsedRealtime()
            delay(50)
        }
        now = SystemClock.elapsedRealtime()
    }
    return now
}
