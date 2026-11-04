package dev.runlet.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.runlet.app.ui.components.IconTile
import dev.runlet.app.ui.components.formatDuration
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.core.engine.RoutineTemplate
import dev.runlet.core.engine.RoutineTemplates
import dev.runlet.core.engine.ScriptParser
import dev.runlet.core.engine.TemplateIcon

@Composable
private fun TemplateIcon.look(): Pair<ImageVector, Color> {
    val x = Runlet.extras
    return when (this) {
        TemplateIcon.FOCUS -> RunletIcons.Timer to x.stage
        TemplateIcon.INTERVAL -> RunletIcons.Fitness to x.volume
        TemplateIcon.BREATHE -> RunletIcons.Heart to x.message
        TemplateIcon.STRETCH -> RunletIcons.Person to x.haptic
        TemplateIcon.TEA -> RunletIcons.Cafe to x.repeat
        TemplateIcon.EYE -> RunletIcons.Eye to x.wait
        TemplateIcon.TIMER -> RunletIcons.Hourglass to x.sound
        TemplateIcon.BLANK -> RunletIcons.Add to x.note
    }
}

/** "Start from…" sheet: a blank routine first, then ready-made templates. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateSheet(onDismiss: () -> Unit, onPick: (RoutineTemplate) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()).testTag("template-sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("New routine", style = MaterialTheme.typography.titleLarge)
            Text(
                "Start from a template and change anything you like.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            (listOf(RoutineTemplates.blank) + RoutineTemplates.all).forEach { t ->
                val (icon, tint) = t.icon.look()
                val total = runCatching { ScriptParser.parse(t.source).totalMs }.getOrNull()
                Surface(
                    onClick = { onPick(t) },
                    color = Color.Transparent,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().testTag("template-${t.id}"),
                ) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(icon, tint)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (total != null) {
                            Text(formatDuration(total), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
