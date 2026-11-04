package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Automation
import dev.runlet.core.model.Day
import dev.runlet.core.model.Trigger
import dev.runlet.core.model.Limits

class ScriptParseException(message: String) : IllegalArgumentException(message)

/**
 * The Runlet routine DSL. Deterministic: no eval, shell or remote code.
 *
 * There is one grammar, [parseOutline], which produces the structure the user wrote (repeat blocks
 * and comments kept, nothing expanded). [parse] builds on it: it expands repeats into a flat
 * [Automation] and enforces the size and time limits, which are deliberately *not* part of the
 * grammar so an editor can still show and fix a script that is merely too long.
 */
object ScriptParser {
    private val registry get() = ActionRegistry.builtIn
    private val QUOTED = Regex("^([a-z]+)\\s+\"([^\"\\r\\n]{1,160})\"(?:\\s+(.+))?$")
    private val DURATION = Regex("^(\\d+)(ms|s|m|h)$")
    private val REPEAT = Regex("repeat\\s+(\\d+)\\s*\\{")
    private val TIME = Regex("^(\\d{2}):(\\d{2})$")
    private val WHITESPACE = Regex("\\s+")
    private val NEWLINE = Regex("\\r?\\n")

    /** Parses one action line such as `stage "Bloom" 30s`. Header lines (routine/alerts) are not actions. */
    fun parseAction(line: String): Action {
        val items = Syntax(listOf(line)).block(nested = false, depth = 0)
        val step = items.singleOrNull() as? OutlineItem.Step
            ?: throw ScriptParseException("Expected a single action")
        return step.action
    }

    /**
     * Parses [source] into the structure the user wrote. Checks syntax, each step's own values, repeat
     * counts and nesting depth, but not the overall action count or duration (see [parse]).
     */
    fun parseOutline(source: String?): Outline {
        if (source == null || source.length > Limits.MAX_SOURCE_CHARS) {
            throw ScriptParseException("Script must be at most 100,000 characters")
        }
        val syntax = Syntax(source.split(NEWLINE))
        val items = syntax.block(nested = false, depth = 0)
        return Outline(syntax.name, syntax.alerts, items, syntax.triggers)
    }

    /** Parses [source] into a runnable, fully expanded [Automation] and enforces all limits. */
    fun parse(source: String?): Automation {
        val outline = parseOutline(source)
        if (outline.items.actionCount() > Limits.MAX_ACTIONS) {
            throw ScriptParseException("Too many actions (maximum 2,000)")
        }
        val actions = ArrayList<Action>()
        expand(outline.items, actions)
        if (actions.isEmpty()) throw ScriptParseException("Add at least one action")
        val automation = Automation(outline.name, outline.alerts, actions, outline.triggers)
        if (automation.totalMs > Limits.MAX_TOTAL_MS) {
            throw ScriptParseException("Total duration must be 4 hours or less")
        }
        return automation
    }

    private fun expand(items: List<OutlineItem>, out: MutableList<Action>) {
        for (item in items) when (item) {
            is OutlineItem.Step -> out.add(item.action)
            is OutlineItem.Repeat -> repeat(item.count) { expand(item.items, out) }
            is OutlineItem.Comment -> Unit
        }
    }

    private class Syntax(private val lines: List<String>) {
        var name = "Untitled routine"
        var alerts = AlertMode.BOTH
        val triggers = ArrayList<Trigger>()
        private var pos = 0

        fun block(nested: Boolean, depth: Int): List<OutlineItem> {
            if (depth > Limits.MAX_NEST_DEPTH) throw error("Repeat nesting is limited to 8 levels")
            val out = ArrayList<OutlineItem>()
            while (pos < lines.size) {
                val line = lines[pos++].trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    out.add(OutlineItem.Comment(line))
                    continue
                }
                if (line == "}") {
                    if (!nested) throw error("Unexpected }")
                    return out
                }
                if (line.startsWith("repeat ")) {
                    val m = REPEAT.matchEntire(line) ?: throw error("Use repeat 3 { on its own line")
                    val n = integer(m.groupValues[1], 1, Limits.MAX_REPEAT)
                    out.add(OutlineItem.Repeat(n, block(nested = true, depth = depth + 1)))
                    continue
                }
                val keyword = line.split(WHITESPACE)[0]
                when (keyword) {
                    "routine" -> {
                        val q = QUOTED.matchEntire(line) ?: throw error("Use routine \"Name\"")
                        if (nested || q.groups[3] != null) throw error("routine belongs outside repeat blocks")
                        name = q.groupValues[2]
                    }
                    "alerts", "when" -> header(line, keyword, nested)
                    else -> {
                        val type = registry.type(keyword) ?: throw error("Unknown command: $keyword")
                        out.add(OutlineItem.Step(type.parse(Line(keyword, line))))
                    }
                }
            }
            if (nested) throw error("Missing closing }")
            return out
        }

        private fun header(line: String, op: String, nested: Boolean) {
            val t = line.split(WHITESPACE)
            when (op) {
                "alerts" -> {
                    val mode = t.getOrNull(1)?.let(AlertMode::fromKeyword)
                    if (nested || t.size != 2 || mode == null) {
                        throw error("Use alerts both|sound|vibration|none outside repeat blocks")
                    }
                    alerts = mode
                }
                else -> {
                    if (nested) throw error("when belongs outside repeat blocks")
                    if (triggers.size >= Limits.MAX_TRIGGERS) throw error("At most ${Limits.MAX_TRIGGERS} triggers")
                    triggers.add(trigger(t.drop(1)))
                }
            }
        }

        /** One action line, as [ActionType.parse] sees it. */
        private inner class Line(override val keyword: String, line: String) : LineContext {
            private val quoted = QUOTED.matchEntire(line)?.takeIf { it.groupValues[1] == keyword }
            override val text: String? = quoted?.groupValues?.get(2)
            override val tail: String? = quoted?.groups?.get(3)?.value
            override val args: List<String> = line.split(WHITESPACE).drop(1)
            override fun fail(message: String): Nothing = throw error(message)
            override fun duration(s: String) = this@Syntax.duration(s)
            override fun integer(s: String, min: Int, max: Int) = this@Syntax.integer(s, min, max)
        }

        private fun trigger(t: List<String>): Trigger = when (t.firstOrNull()) {
            "boot" -> if (t.size == 1) Trigger.Boot else throw error("when boot takes no arguments")
            "time" -> {
                val m = TIME.matchEntire(t.getOrNull(1) ?: "")
                    ?: throw error("Use when time 07:30 [daily|weekdays|weekends|mon,wed]")
                val hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toInt()
                if (hour > 23 || minute > 59) throw error("Time must be between 00:00 and 23:59")
                if (t.size > 3) throw error("Too many words after the time")
                Trigger.Time(hour, minute, t.getOrNull(2)?.let(::days) ?: Day.ALL)
            }
            else -> throw error("Use when time|boot ...")
        }

        private fun days(s: String): Set<Day> = when (s) {
            "daily" -> Day.ALL
            "weekdays" -> Day.WEEKDAYS
            "weekends" -> Day.WEEKENDS
            else -> s.split(',').map { Day.fromKeyword(it) ?: throw error("Unknown day: $it") }.toSet()
        }

        private fun integer(s: String, min: Int, max: Int): Int {
            val n = s.toIntOrNull()
            if (n != null && n in min..max) return n
            throw error("Number must be between $min and $max")
        }

        private fun duration(s: String): Long {
            val m = DURATION.matchEntire(s) ?: throw error("Use a positive duration such as 200ms, 30s, 5m, 1h")
            val n = m.groupValues[1].toLongOrNull() ?: throw error("Duration is too large")
            val factor = when (m.groupValues[2]) {
                "ms" -> 1L
                "s" -> 1_000L
                "m" -> 60_000L
                else -> 3_600_000L
            }
            if (n <= 0 || n > Limits.MAX_TOTAL_MS / factor) throw error("Duration must be positive and at most 4 hours")
            return n * factor
        }

        private fun error(message: String) = ScriptParseException("Line $pos: $message")
    }

    const val COFFEE = "# Your first automation · 4 minutes 50 seconds\nroutine \"French press\"\nalerts both\n\nstage \"Bloom\" 30s\nstage \"Steep\" 210s\nstage \"Press\" 20s\nstage \"Pour\" 30s\n"
    const val DEMO = "routine \"Quick test\"\nalerts both\nlog \"Testing your phone alerts\"\nstage \"First stage\" 3s\nrepeat 2 {\n  stage \"Repeat stage\" 2s\n}\nnotify \"All actions ran successfully\"\n"
    const val YOGA = "# Paste the real poses and durations here.\n# The video has not yet been transcribed.\n# https://youtu.be/dAqQqmaI9vY\nroutine \"Morning yoga\"\nalerts both\n\n# Syntax: stage \"Pose name\" 30s\n"
}

/** Helpers for editing script text without a full parse. */
object ScriptText {
    private val ROUTINE_LINE = Regex("""^[ \t]*routine\s+"[^"\r\n]{1,160}"[ \t]*$""", RegexOption.MULTILINE)

    /** Name from the first `routine "…"` line, or null when there is none. */
    fun routineName(source: String): String? =
        ROUTINE_LINE.find(source)?.value?.substringAfter('"')?.substringBeforeLast('"')

    /** Replaces the `routine` line with [name], or adds one at the top when the script has none. */
    fun renameRoutine(source: String, name: String): String {
        val line = "routine \"$name\""
        return if (ROUTINE_LINE.containsMatchIn(source)) ROUTINE_LINE.replaceFirst(source, Regex.escapeReplacement(line))
        else "$line\n$source"
    }

    /** True when [name] can be written into a `routine` line. */
    fun isValidName(name: String): Boolean =
        name.isNotBlank() && name.trim().length <= 80 && name.none { it == '"' || it.isISOControl() }
}
