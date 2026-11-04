package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.Limits
import kotlin.reflect.KClass

private fun duration(ms: Long): String = when {
    ms % 3_600_000 == 0L -> "${ms / 3_600_000} h"
    ms % 60_000 == 0L -> "${ms / 60_000} min"
    ms % 1_000 == 0L -> "${ms / 1_000} s"
    else -> "$ms ms"
}

/** The seven actions of the Java prototype, ported as registry plugins. */
object BuiltInActions {
    object StageType : ActionType<Action.Stage> {
        override val id = "stage"
        override val kind: KClass<Action.Stage> = Action.Stage::class
        override val description = "Announce a named stage with an alert, then wait"
        override val title = "Stage"
        override val hint = "A named step with a time"
        override fun newAction() = Action.Stage("New stage", 30_000)
        override fun parse(line: LineContext): Action.Stage {
            val label = line.text ?: line.fail("Use stage \"Name\" 30s")
            val duration = line.tail ?: line.fail("Stage needs a duration, e.g. 30s")
            return Action.Stage(label, line.duration(duration))
        }
        override fun print(action: Action.Stage) = "stage \"${action.label}\" ${ScriptPrinter.duration(action.durationMs)}"
        override fun summary(action: Action.Stage) = "Stage “${action.label}” for ${duration(action.durationMs)}"
        override suspend fun execute(action: Action.Stage, ctx: ExecutionContext) {
            ctx.setLabel(action.label)
            ctx.log("${action.label} · ${formatClock(action.durationMs)}")
            ctx.alert()
            ctx.sleep(action.durationMs)
        }
    }

    object WaitType : ActionType<Action.Wait> {
        override val id = "wait"
        override val kind: KClass<Action.Wait> = Action.Wait::class
        override val description = "Pause silently"
        override val title = "Wait"
        override val hint = "A silent pause"
        override fun newAction() = Action.Wait(10_000)
        override fun parse(line: LineContext): Action.Wait {
            if (line.args.size != 1) line.fail("Use wait 1s")
            return Action.Wait(line.duration(line.args[0]))
        }
        override fun print(action: Action.Wait) = "wait ${ScriptPrinter.duration(action.durationMs)}"
        override fun summary(action: Action.Wait) = "Wait ${duration(action.durationMs)}"
        override suspend fun execute(action: Action.Wait, ctx: ExecutionContext) {
            ctx.setLabel("Wait")
            ctx.log("Wait · ${formatClock(action.durationMs)}")
            ctx.sleep(action.durationMs)
        }
    }

    object ChimeType : ActionType<Action.Chime> {
        override val id = "chime"
        override val kind: KClass<Action.Chime> = Action.Chime::class
        override val description = "Play a short chime"
        override val title = "Chime"
        override val hint = "Play a short sound"
        override fun newAction() = Action.Chime
        override fun parse(line: LineContext): Action.Chime {
            if (line.args.isNotEmpty()) line.fail("chime takes no arguments")
            return Action.Chime
        }
        override fun print(action: Action.Chime) = "chime"
        override val capabilities = setOf(Capability.SOUND)
        override fun summary(action: Action.Chime) = "Play a chime"
        override suspend fun execute(action: Action.Chime, ctx: ExecutionContext) {
            ctx.platform.chime()
            ctx.log("Chime")
        }
    }

    object VibrateType : ActionType<Action.Vibrate> {
        override val id = "vibrate"
        override val kind: KClass<Action.Vibrate> = Action.Vibrate::class
        override val description = "Vibrate for up to 5 seconds"
        override val title = "Vibrate"
        override val hint = "Buzz for up to 5 seconds"
        override fun newAction() = Action.Vibrate(500)
        override fun parse(line: LineContext): Action.Vibrate {
            if (line.args.size != 1) line.fail("Use vibrate 1s")
            val d = line.duration(line.args[0])
            if (d > Limits.MAX_VIBRATE_MS) line.fail("Vibration maximum is 5 seconds")
            return Action.Vibrate(d)
        }
        override fun print(action: Action.Vibrate) = "vibrate ${ScriptPrinter.duration(action.durationMs)}"
        override val capabilities = setOf(Capability.VIBRATION)
        override fun summary(action: Action.Vibrate) = "Vibrate for ${duration(action.durationMs)}"
        override suspend fun execute(action: Action.Vibrate, ctx: ExecutionContext) {
            ctx.platform.vibrate(action.durationMs)
            ctx.log("Vibration: ${action.durationMs}ms")
        }
    }

    object NotifyType : ActionType<Action.Notify> {
        override val id = "notify"
        override val kind: KClass<Action.Notify> = Action.Notify::class
        override val description = "Post a notification"
        override val title = "Notification"
        override val hint = "Show a message on the phone"
        override fun newAction() = Action.Notify("Time's up")
        override fun parse(line: LineContext): Action.Notify {
            val message = line.text ?: line.fail("Use notify \"Message\"")
            if (line.tail != null) line.fail("Unexpected text after closing quote")
            return Action.Notify(message)
        }
        override fun print(action: Action.Notify) = "notify \"${action.message}\""
        override val capabilities = setOf(Capability.NOTIFICATIONS)
        override fun summary(action: Action.Notify) = "Notify “${action.message}”"
        override suspend fun execute(action: Action.Notify, ctx: ExecutionContext) {
            ctx.platform.notify(ctx.automation.name, action.message)
            ctx.log("Notification: ${action.message}")
        }
    }

    object VolumeType : ActionType<Action.Volume> {
        override val id = "volume"
        override val kind: KClass<Action.Volume> = Action.Volume::class
        override val description = "Set media volume (0–100%)"
        override val title = "Media volume"
        override val hint = "Set the volume level"
        override fun newAction() = Action.Volume(50)
        override fun parse(line: LineContext): Action.Volume {
            if (line.args.size != 1) line.fail("Use volume 35 (0–100 percent, media volume)")
            return Action.Volume(line.integer(line.args[0], 0, 100))
        }
        override fun print(action: Action.Volume) = "volume ${action.percent}"
        override val capabilities = setOf(Capability.AUDIO_SETTINGS)
        override fun summary(action: Action.Volume) = "Set media volume to ${action.percent}%"
        override suspend fun execute(action: Action.Volume, ctx: ExecutionContext) {
            ctx.platform.setMediaVolume(action.percent)
            ctx.log("Media volume: ${action.percent}%")
        }
    }

    object LogType : ActionType<Action.Log> {
        override val id = "log"
        override val kind: KClass<Action.Log> = Action.Log::class
        override val description = "Write a line to the activity log"
        override val title = "Log entry"
        override val hint = "Write to the run history"
        override fun newAction() = Action.Log("Checkpoint")
        override fun parse(line: LineContext): Action.Log {
            val message = line.text ?: line.fail("Use log \"Message\"")
            if (line.tail != null) line.fail("Unexpected text after closing quote")
            return Action.Log(message)
        }
        override fun print(action: Action.Log) = "log \"${action.message}\""
        override fun summary(action: Action.Log) = "Log “${action.message}”"
        override suspend fun execute(action: Action.Log, ctx: ExecutionContext) = ctx.log(action.message)
    }

    val all: List<ActionType<*>> = listOf(StageType, WaitType, NotifyType, ChimeType, VibrateType, VolumeType, LogType)
}
