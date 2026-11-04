package dev.runlet.core.engine

/** Which glyph a template uses; the UI maps these to icons. */
enum class TemplateIcon { FOCUS, INTERVAL, BREATHE, STRETCH, TEA, EYE, TIMER, BLANK }

/** A ready-made routine a user can start from. [source] is DSL text. */
data class RoutineTemplate(
    val id: String,
    val title: String,
    val tagline: String,
    val icon: TemplateIcon,
    val source: String,
)

object RoutineTemplates {
    val blank = RoutineTemplate(
        id = "blank",
        title = "Blank routine",
        tagline = "Start from scratch",
        icon = TemplateIcon.BLANK,
        source = "routine \"New routine\"\nalerts both\n",
    )

    val all: List<RoutineTemplate> = listOf(
        RoutineTemplate(
            "pomodoro", "Pomodoro", "4 × 25 min focus with breaks", TemplateIcon.FOCUS,
            "routine \"Pomodoro\"\nalerts both\n\nrepeat 4 {\n  stage \"Focus\" 25m\n  stage \"Short break\" 5m\n}\n" +
                "stage \"Long break\" 15m\nnotify \"Pomodoro set complete\"\n",
        ),
        RoutineTemplate(
            "interval", "Interval training", "8 rounds of 30 s work, 15 s rest", TemplateIcon.INTERVAL,
            "routine \"Interval training\"\nalerts both\n\nstage \"Warm up\" 2m\nrepeat 8 {\n  stage \"Work\" 30s\n  stage \"Rest\" 15s\n}\n" +
                "stage \"Cool down\" 2m\nnotify \"Workout complete\"\n",
        ),
        RoutineTemplate(
            "breathing", "Box breathing", "4 – 4 – 4 – 4, four rounds", TemplateIcon.BREATHE,
            "routine \"Box breathing\"\nalerts vibration\n\nrepeat 4 {\n  stage \"Inhale\" 4s\n  stage \"Hold\" 4s\n" +
                "  stage \"Exhale\" 4s\n  stage \"Hold\" 4s\n}\nnotify \"Well done\"\n",
        ),
        RoutineTemplate(
            "stretch", "Desk stretch", "Two minutes to loosen up", TemplateIcon.STRETCH,
            "routine \"Desk stretch\"\nalerts both\n\nstage \"Neck\" 30s\nstage \"Shoulders\" 30s\nstage \"Wrists\" 30s\n" +
                "stage \"Back\" 30s\nnotify \"Stretch complete\"\n",
        ),
        RoutineTemplate(
            "tea", "Tea timer", "Steep for three minutes", TemplateIcon.TEA,
            "routine \"Tea timer\"\nalerts both\n\nstage \"Steep\" 3m\nnotify \"Your tea is ready\"\n",
        ),
        RoutineTemplate(
            "eyes", "Eye rest", "The 20-20-20 rule, three times", TemplateIcon.EYE,
            "routine \"Eye rest\"\nalerts sound\n\nrepeat 3 {\n  wait 20m\n  stage \"Look 20 ft away\" 20s\n}\n",
        ),
        RoutineTemplate(
            "timer", "Quick timer", "Five minutes, then a chime", TemplateIcon.TIMER,
            "routine \"Quick timer\"\nalerts both\n\nstage \"Timer\" 5m\n",
        ),
    )

    fun byId(id: String): RoutineTemplate? = (all + blank).firstOrNull { it.id == id }
}
