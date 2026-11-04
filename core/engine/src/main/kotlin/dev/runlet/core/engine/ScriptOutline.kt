package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Day
import dev.runlet.core.model.Limits
import dev.runlet.core.model.Trigger

/** Prints actions back to DSL text. The inverse of [ScriptParser] for single actions. */
object ScriptPrinter {
    /** Largest unit that divides [ms] exactly, e.g. 90_000 -> "90s", 120_000 -> "2m". */
    fun duration(ms: Long): String = when {
        ms % 3_600_000 == 0L -> "${ms / 3_600_000}h"
        ms % 60_000 == 0L -> "${ms / 60_000}m"
        ms % 1_000 == 0L -> "${ms / 1_000}s"
        else -> "${ms}ms"
    }

    fun trigger(trigger: Trigger): String = when (trigger) {
        Trigger.Boot -> "when boot"
        is Trigger.Time -> {
            val time = "%02d:%02d".format(trigger.hour, trigger.minute)
            val days = when (trigger.days) {
                Day.ALL -> ""
                Day.WEEKDAYS -> " weekdays"
                Day.WEEKENDS -> " weekends"
                else -> " " + Day.entries.filter { it in trigger.days }.joinToString(",") { it.keyword }
            }
            "when time $time$days"
        }
    }

    /** Plain-English form for cards, e.g. "Weekdays at 07:30". */
    fun describe(trigger: Trigger): String = when (trigger) {
        Trigger.Boot -> "When the phone starts"
        is Trigger.Time -> {
            val days = when (trigger.days) {
                Day.ALL -> "Every day"
                Day.WEEKDAYS -> "Weekdays"
                Day.WEEKENDS -> "Weekends"
                else -> Day.entries.filter { it in trigger.days }.joinToString(", ") { it.keyword.replaceFirstChar(Char::uppercase) }
            }
            "$days at ${"%02d:%02d".format(trigger.hour, trigger.minute)}"
        }
    }

    fun line(action: Action): String = ActionRegistry.builtIn.print(action)
}

/** One entry in a script as the user wrote it: repeat blocks and comments are kept, not expanded. */
sealed interface OutlineItem {
    data class Step(val action: Action) : OutlineItem
    data class Repeat(val count: Int, val items: List<OutlineItem>) : OutlineItem
    /** A full-line comment, including its leading `#`. */
    data class Comment(val text: String) : OutlineItem
}

private fun satAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
private fun satMul(a: Long, b: Long): Long = if (a == 0L || b == 0L) 0L else if (a > Long.MAX_VALUE / b) Long.MAX_VALUE else a * b

/** Scheduled time of these items with repeats counted, saturating instead of overflowing. */
fun List<OutlineItem>.totalMs(): Long = fold(0L) { acc, item ->
    satAdd(
        acc,
        when (item) {
            is OutlineItem.Step -> item.action.scheduledMs
            is OutlineItem.Repeat -> satMul(item.count.toLong(), item.items.totalMs())
            is OutlineItem.Comment -> 0L
        },
    )
}

/** Number of actions these items expand to, saturating instead of overflowing. */
fun List<OutlineItem>.actionCount(): Long = fold(0L) { acc, item ->
    satAdd(
        acc,
        when (item) {
            is OutlineItem.Step -> 1L
            is OutlineItem.Repeat -> satMul(item.count.toLong(), item.items.actionCount())
            is OutlineItem.Comment -> 0L
        },
    )
}

/**
 * Editable, source-preserving view of a script. The Basic editor changes this structure and
 * prints it back, so `repeat` blocks and comments survive edits. Blank lines are not preserved.
 */
data class Outline(
    val name: String,
    val alerts: AlertMode,
    val items: List<OutlineItem>,
    val triggers: List<Trigger> = emptyList(),
) {
    val totalMs: Long get() = items.totalMs()
    val actionCount: Long get() = items.actionCount()

    /** DSL text for this outline. */
    fun print(): String = buildString {
        append("routine \"").append(name).append("\"\n")
        append("alerts ").append(alerts.keyword).append("\n")
        for (t in triggers) append(ScriptPrinter.trigger(t)).append("\n")
        append("\n")
        printItems(items, 0)
    }

    private fun StringBuilder.printItems(list: List<OutlineItem>, depth: Int) {
        val pad = "  ".repeat(depth)
        for (item in list) when (item) {
            is OutlineItem.Step -> append(pad).append(ScriptPrinter.line(item.action)).append('\n')
            is OutlineItem.Comment -> append(pad).append(item.text).append('\n')
            is OutlineItem.Repeat -> {
                append(pad).append("repeat ").append(item.count).append(" {\n")
                printItems(item.items, depth + 1)
                append(pad).append("}\n")
            }
        }
    }

    /** Adds [trigger], ignoring duplicates and anything past the limit. */
    fun addTrigger(trigger: Trigger): Outline =
        if (trigger in triggers || triggers.size >= Limits.MAX_TRIGGERS) this else copy(triggers = triggers + trigger)

    fun removeTrigger(index: Int): Outline =
        if (index !in triggers.indices) this else copy(triggers = triggers.filterIndexed { i, _ -> i != index })

    fun replaceTrigger(index: Int, trigger: Trigger): Outline =
        if (index !in triggers.indices) this else copy(triggers = triggers.mapIndexed { i, t -> if (i == index) trigger else t })

    /** Inserts [item] at [index] inside the container at [parent] (empty path = top level). */
    fun insert(parent: List<Int>, index: Int, item: OutlineItem): Outline =
        copy(items = edit(items, parent) { it.toMutableList().apply { add(index.coerceIn(0, size), item) } })

    fun remove(path: List<Int>): Outline =
        copy(items = edit(items, path.dropLast(1)) { it.toMutableList().apply { removeAt(path.last()) } })

    fun replace(path: List<Int>, item: OutlineItem): Outline =
        copy(items = edit(items, path.dropLast(1)) { it.toMutableList().apply { set(path.last(), item) } })

    /** Moves the item at [path] by [delta] positions within its container; no-op at the ends. */
    fun move(path: List<Int>, delta: Int): Outline {
        val from = path.last()
        return copy(items = edit(items, path.dropLast(1)) { list ->
            val to = from + delta
            if (to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
        })
    }

    fun itemAt(path: List<Int>): OutlineItem? {
        var list = items
        var item: OutlineItem? = null
        for ((i, idx) in path.withIndex()) {
            item = list.getOrNull(idx) ?: return null
            if (i < path.lastIndex) list = (item as? OutlineItem.Repeat)?.items ?: return null
        }
        return item
    }

    private fun edit(
        list: List<OutlineItem>,
        containerPath: List<Int>,
        change: (List<OutlineItem>) -> List<OutlineItem>,
    ): List<OutlineItem> {
        if (containerPath.isEmpty()) return change(list)
        val head = containerPath.first()
        val target = list.getOrNull(head) as? OutlineItem.Repeat ?: return list
        val rewritten = target.copy(items = edit(target.items, containerPath.drop(1), change))
        return list.toMutableList().apply { set(head, rewritten) }
    }

    companion object {
        /** Parses [source] into an outline; throws [ScriptParseException] with a line number if it is malformed. */
        fun parse(source: String): Outline = ScriptParser.parseOutline(source)
    }
}
