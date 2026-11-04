package dev.runlet.app.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.runlet.app.ui.components.IconTile
import dev.runlet.app.ui.components.SectionLabel
import dev.runlet.app.ui.theme.Runlet
import dev.runlet.app.ui.theme.RunletIcons
import dev.runlet.app.ui.theme.RunletText

@Composable
fun GuideScreen(onOpenSettings: () -> Unit, contentPadding: PaddingValues) {
    val x = Runlet.extras
    LazyColumn(
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().testTag("guide"),
    ) {
        item {
            Text("Guide", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Small routines. Useful timing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Card(RunletIcons.Timer, x.stage, "How routines work") {
                Body(
                    "A routine is a list of steps that run one after another. Stages show a name and count down, " +
                        "waits pause silently, and other steps play sounds, vibrate or post a notification. " +
                        "Tap a routine to edit it, or press play to run it.",
                )
            }
        }
        item {
            Card(RunletIcons.Code, x.codeNumber, "The script view") {
                Body("Switch the editor to Script for full control. Every routine is a small text file you can read, edit, share and back up.")
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Text(
                            "routine \"Intervals\"\nalerts both\nstage \"Warm up\" 2m\nrepeat 8 {\n  stage \"Work\" 30s\n  stage \"Rest\" 15s\n}\nnotify \"Well done\"",
                            style = RunletText.code,
                            color = x.codeKeyword,
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            Card(RunletIcons.Log, x.note, "Commands") {
                Command("stage \"Name\" 30s", "A named step that counts down, then moves on")
                Command("wait 2s", "A silent pause with no start alert")
                Command("chime", "A short notification sound")
                Command("vibrate 200ms", "Buzz, for at most 5 seconds")
                Command("notify \"Message\"", "Post a notification")
                Command("volume 35", "Set media volume to 35%")
                Command("log \"Message\"", "Add a line to the run history")
                Command("repeat 3 { … }", "Run a block several times")
                Command("alerts both", "both, sound, vibration or none")
                Spacer(Modifier.height(10.dp))
                Body("Durations use ms, s, m or h. Put each brace on its own line. A routine can hold up to 2,000 steps and 4 hours of waiting.")
            }
        }
        item {
            Card(RunletIcons.Notifications, x.sound, "Keeping runs alive") {
                Body(
                    "Allow notifications, and in your phone's battery settings allow Runlet to run in the background " +
                        "(on some phones this is called “unrestricted”). Do Not Disturb and low notification volume can silence chimes.\n\n" +
                        "A running routine keeps the CPU awake. If Android kills the app, or you restart the phone, the run stops and is marked " +
                        "interrupted in Activity; it does not resume yet.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.testTag("app-settings")) {
                    Icon(RunletIcons.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open app settings")
                }
            }
        }
    }
}

@Composable
private fun Card(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, tint, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Command(code: String, what: String) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(code, style = RunletText.code, color = Runlet.extras.codeKeyword)
        Text(what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
