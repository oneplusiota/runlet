package dev.runlet.core.model

/** How a stage announces itself. */
enum class AlertMode(val keyword: String) {
    BOTH("both"), SOUND("sound"), VIBRATION("vibration"), NONE("none");

    companion object {
        fun fromKeyword(keyword: String): AlertMode? = entries.firstOrNull { it.keyword == keyword }
    }
}

/** A single typed step. Replaces the stringly-typed `Step(op, text, value)` of the Java prototype. */
sealed interface Action {
    /** Alerts on entry (per [AutomationAlerts]) then waits. */
    data class Stage(val label: String, val durationMs: Long) : Action
    data class Wait(val durationMs: Long) : Action
    data object Chime : Action
    data class Vibrate(val durationMs: Long) : Action
    data class Notify(val message: String) : Action
    data class Volume(val percent: Int) : Action
    data class Log(val message: String) : Action

    /** Time this action occupies on the clock (only stages and waits take scheduled time). */
    val scheduledMs: Long
        get() = when (this) {
            is Stage -> durationMs
            is Wait -> durationMs
            else -> 0L
        }
}

enum class Day(val keyword: String) {
    MON("mon"), TUE("tue"), WED("wed"), THU("thu"), FRI("fri"), SAT("sat"), SUN("sun");

    companion object {
        val ALL: Set<Day> = entries.toSet()
        val WEEKDAYS: Set<Day> = setOf(MON, TUE, WED, THU, FRI)
        val WEEKENDS: Set<Day> = setOf(SAT, SUN)
        fun fromKeyword(keyword: String): Day? = entries.firstOrNull { it.keyword == keyword }
    }
}

/** Something that starts an automation by itself. Written as `when ...` lines in the DSL. */
sealed interface Trigger {
    /** Fires at [hour]:[minute] local time on each of [days]. */
    data class Time(val hour: Int, val minute: Int, val days: Set<Day> = Day.ALL) : Trigger
    data object Boot : Trigger
}

/** Canonical automation document. Conditions/variables arrive in later phases. */
data class Automation(
    val name: String,
    val alerts: AlertMode,
    val actions: List<Action>,
    val triggers: List<Trigger> = emptyList(),
) {
    val totalMs: Long = actions.sumOf { it.scheduledMs }
}

object Limits {
    const val MAX_SOURCE_CHARS = 100_000
    const val MAX_ACTIONS = 2_000
    const val MAX_TOTAL_MS = 4L * 60 * 60 * 1000
    const val MAX_NEST_DEPTH = 8
    const val MAX_REPEAT = 100
    const val MAX_VIBRATE_MS = 5_000L
    const val MAX_LABEL_CHARS = 160
    const val MAX_TRIGGERS = 20
}
