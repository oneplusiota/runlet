package dev.runlet.core.engine

import dev.runlet.core.model.Action
import dev.runlet.core.model.AlertMode
import dev.runlet.core.model.Automation
import kotlin.reflect.KClass

/** What an action can see and do while it executes. */
interface ExecutionContext {
    val automation: Automation
    val platform: Platform
    fun log(message: String) = platform.log(message)
    /** Shows [label] as the current stage in the UI and notification. */
    fun setLabel(label: String)
    /** Plays the automation's configured entry alert (sound and/or vibration). */
    fun alert()
    /** Waits [ms], honouring pause/resume and stop. */
    suspend fun sleep(ms: Long)
}

/** One script line as an action type sees it while parsing, with the parser's line-numbered errors. */
interface LineContext {
    val keyword: String
    /** Text inside the first pair of quotes, or null when the line has none. */
    val text: String?
    /** Whatever follows the closing quote, or null. */
    val tail: String?
    /** Whitespace-separated words after the keyword. */
    val args: List<String>
    fun fail(message: String): Nothing
    /** Parses `200ms`, `30s`, `5m` or `1h`. */
    fun duration(s: String): Long
    fun integer(s: String, min: Int, max: Int): Int
}

/**
 * One kind of action. Adding a capability means writing one of these and registering it:
 * the DSL keyword, plain-English summary and permission needs all hang off the same class.
 */
interface ActionType<A : Action> {
    /** Stable id; equals the DSL keyword. */
    val id: String
    val kind: KClass<A>
    val description: String
    /** Name in the add-step sheet. */
    val title: String
    /** One-line hint under [title] in the add-step sheet. */
    val hint: String
    val capabilities: Set<Capability> get() = emptySet()
    /** What a freshly added step starts as. */
    fun newAction(): A
    /** Reads the line [line] into an action. Report problems with [LineContext.fail]. */
    fun parse(line: LineContext): A
    /** The script line for [action]; [parse] of this text gives [action] back. */
    fun print(action: A): String
    /** Plain-English summary for the Basic UI. */
    fun summary(action: A): String
    suspend fun execute(action: A, ctx: ExecutionContext)
}

class ActionRegistry {
    private val byKind = LinkedHashMap<KClass<out Action>, ActionType<out Action>>()
    private val byId = HashMap<String, ActionType<out Action>>()

    fun <A : Action> register(type: ActionType<A>): ActionRegistry {
        require(type.id !in byId) { "Action ${type.id} already registered" }
        require(byKind.putIfAbsent(type.kind, type) == null) { "Action ${type.id} already registered" }
        byId[type.id] = type
        return this
    }

    /** The type whose script keyword is [keyword], or null. */
    fun type(keyword: String): ActionType<out Action>? = byId[keyword]

    fun print(action: Action): String = typeFor(action).print(action)

    fun all(): List<ActionType<out Action>> = byKind.values.toList()

    @Suppress("UNCHECKED_CAST")
    private fun <A : Action> typeFor(action: A): ActionType<A> =
        (byKind[action::class] as? ActionType<A>)
            ?: error("No action type registered for ${action::class.simpleName}")

    suspend fun execute(action: Action, ctx: ExecutionContext) = typeFor(action).execute(action, ctx)

    fun summary(action: Action): String = typeFor(action).summary(action)

    /** Every capability the automation needs, for the permission broker. */
    fun requiredCapabilities(automation: Automation): Set<Capability> {
        val needed = linkedSetOf<Capability>()
        for (a in automation.actions) needed += typeFor(a).capabilities
        // Stages alert on entry according to the automation's alert mode.
        if (automation.actions.any { it is Action.Stage }) {
            if (automation.alerts == AlertMode.BOTH || automation.alerts == AlertMode.SOUND) needed += Capability.SOUND
            if (automation.alerts == AlertMode.BOTH || automation.alerts == AlertMode.VIBRATION) needed += Capability.VIBRATION
        }
        return needed
    }

    companion object {
        /** Shared instance the script parser and printer use. */
        val builtIn: ActionRegistry by lazy { default() }

        fun default(): ActionRegistry = ActionRegistry().apply { BuiltInActions.all.forEach { registerAny(it) } }

        @Suppress("UNCHECKED_CAST")
        private fun ActionRegistry.registerAny(type: ActionType<*>) = register(type as ActionType<Action>)
    }
}
