package dev.runlet.app.ui.run

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.runlet.app.ui.components.Pill
import dev.runlet.app.ui.components.clockText
import dev.runlet.app.ui.components.formatDuration
import dev.runlet.app.ui.components.rememberNowMs
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.app.ui.theme.RunletText
import dev.runlet.core.engine.RunState
import dev.runlet.core.engine.RunStatus

/** Time left in the current step, counted from the runner's deadline so the display can't drift from the engine. */
private fun stepRemaining(s: RunState, now: Long): Long = when (s.status) {
    RunStatus.RUNNING -> (s.deadlineMs - now).coerceIn(0, s.stepTotalMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    RunStatus.PAUSED -> s.remainingMs
    else -> 0
}

@Composable
fun RunScreen(
    state: RunState,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = state.status == RunStatus.RUNNING
    val now = rememberNowMs(running)
    val remaining = stepRemaining(state, now)
    val finished = state.status == RunStatus.COMPLETED || state.status == RunStatus.ERROR
    val failed = state.status == RunStatus.ERROR

    val stepFraction = if (state.stepTotalMs > 0 && !finished) remaining.toFloat() / state.stepTotalMs else 0f
    val doneMs = (state.elapsedBeforeStepMs + (state.stepTotalMs - remaining).coerceAtLeast(0)).coerceAtMost(state.totalMs)
    val overall = when {
        state.status == RunStatus.COMPLETED -> 1f
        state.totalMs > 0 -> (doneMs.toFloat() / state.totalMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val animatedOverall by animateFloatAsState(overall, tween(250), label = "overall")

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            // Drawn above the Scaffold, so it has to respect the status and navigation bars itself.
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp).testTag("run-screen"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("run-close")) {
                    Icon(RunletIcons.ExpandMore, contentDescription = "Minimise")
                }
                Spacer(Modifier.weight(1f))
                Pill(
                    when (state.status) {
                        RunStatus.RUNNING -> "Running"
                        RunStatus.PAUSED -> "Paused"
                        RunStatus.COMPLETED -> "Complete"
                        RunStatus.ERROR -> "Stopped"
                        RunStatus.IDLE -> "Ready"
                    },
                    when (state.status) {
                        RunStatus.RUNNING -> Runlet.extras.success
                        RunStatus.PAUSED -> Runlet.extras.sound
                        RunStatus.COMPLETED -> Runlet.extras.success
                        else -> Runlet.extras.warning
                    },
                    modifier = Modifier.testTag("run-pill"),
                )
                Spacer(Modifier.width(8.dp))
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    state.automationName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(20.dp))
                ProgressRing(stepFraction, animatedOverall, paused = state.status == RunStatus.PAUSED, done = finished) {
                    when {
                        failed -> Icon(RunletIcons.Warning, null, Modifier.size(56.dp), tint = Runlet.extras.warning)
                        state.status == RunStatus.COMPLETED ->
                            Icon(RunletIcons.CheckCircle, null, Modifier.size(72.dp), tint = Runlet.extras.success)
                        else -> Text(
                            clockText(remaining),
                            style = RunletText.timerHuge,
                            modifier = Modifier.testTag("run-timer"),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    when {
                        failed -> "Something went wrong"
                        state.status == RunStatus.COMPLETED -> "All done"
                        else -> state.label
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("run-label"),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        failed -> state.error.orEmpty()
                        state.status == RunStatus.COMPLETED -> "${state.automationName} finished in ${formatDuration(state.totalMs)}."
                        state.nextLabel != null -> "Up next: ${state.nextLabel}"
                        else -> "Last step"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("run-next"),
                )
                if (!finished && state.totalMs > 0) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "${clockText((state.totalMs - doneMs).coerceAtLeast(0))} left in total",
                        style = RunletText.timerSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("run-total-left"),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(bottom = 32.dp, top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (finished) {
                    FilledTonalButton(onClick = onClose, modifier = Modifier.height(56.dp).widthIn(min = 200.dp).testTag("run-done")) {
                        Text("Done")
                    }
                } else {
                    OutlinedButton(onClick = onStop, modifier = Modifier.height(56.dp).testTag("run-stop")) {
                        Icon(RunletIcons.Stop, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                    Spacer(Modifier.width(20.dp))
                    FilledIconButton(
                        onClick = onPauseResume,
                        modifier = Modifier.size(80.dp).testTag("run-pause").semantics {
                            contentDescription = if (state.status == RunStatus.PAUSED) "Resume" else "Pause"
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            if (state.status == RunStatus.PAUSED) RunletIcons.Play else RunletIcons.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Two concentric arcs: the outer one is the whole routine's progress, the inner one is the time left in the
 * current step. [content] sits in the middle.
 */
@Composable
private fun ProgressRing(
    stepFraction: Float,
    overall: Float,
    paused: Boolean,
    done: Boolean,
    content: @Composable () -> Unit,
) {
    val track = Runlet.extras.ringTrack
    val outer = if (done) Runlet.extras.success else MaterialTheme.colorScheme.primary
    val inner = if (paused) Runlet.extras.sound else Runlet.extras.wait
    val animatedStep by animateFloatAsState(stepFraction, tween(120), label = "step")
    Box(Modifier.fillMaxWidth(0.82f).widthIn(max = 340.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outerW = 12.dp.toPx()
            val innerW = 8.dp.toPx()
            val gap = 14.dp.toPx()
            val o = outerW / 2
            drawArc(track, 0f, 360f, false, Offset(o, o), Size(size.width - outerW, size.height - outerW), style = Stroke(outerW))
            drawArc(
                outer, -90f, 360f * overall, false, Offset(o, o), Size(size.width - outerW, size.height - outerW),
                style = Stroke(outerW, cap = StrokeCap.Round),
            )
            val i = outerW + gap + innerW / 2
            drawArc(track, 0f, 360f, false, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i), style = Stroke(innerW))
            if (!done) {
                drawArc(
                    inner, -90f, 360f * animatedStep, false, Offset(i, i), Size(size.width - 2 * i, size.height - 2 * i),
                    style = Stroke(innerW, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

/** Slim bar shown above the navigation while a run is active and the full screen is minimised. */
@Composable
fun MiniRunBar(state: RunState, onOpen: () -> Unit, onPauseResume: () -> Unit, modifier: Modifier = Modifier) {
    val running = state.status == RunStatus.RUNNING
    val now = rememberNowMs(running)
    val remaining = stepRemaining(state, now)
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag("mini-run"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.automationName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(clockText(remaining), style = RunletText.timerMedium.copy(fontSize = 22.sp))
            IconButton(onClick = onPauseResume, modifier = Modifier.testTag("mini-pause")) {
                Icon(
                    if (state.status == RunStatus.PAUSED) RunletIcons.Play else RunletIcons.Pause,
                    contentDescription = if (state.status == RunStatus.PAUSED) "Resume" else "Pause",
                )
            }
        }
    }
}
