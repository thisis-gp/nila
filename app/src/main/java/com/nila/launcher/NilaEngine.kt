package com.nila.launcher

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class Choice(
    val title: String,
    val detail: String,
    val price: String,
    val eta: String,
)

data class AsyncChoice(
    val title: String,
    val detail: String,
    val price: String,
    val eta: String,
    val imageUrl: String? = null,
    val onClick: () -> Unit,
)

/** One provider's results inside a GroupedChoices turn. Empty items + a note means that provider has nothing to show yet (e.g. MCP access pending). */
data class ProviderGroup(
    val providerName: String,
    val summary: String,
    val items: List<AsyncChoice>,
    val note: String? = null,
)

/** A product card with a +/− quantity stepper instead of a single tap. onQuantityChange is called with the new count, including 0 to remove. */
data class PickableProduct(
    val title: String,
    val detail: String,
    val price: String,
    val imageUrl: String? = null,
    val initialQuantity: Int = 0,
    val onQuantityChange: (Int) -> Unit,
)

/** One provider's results inside a ProductGroupChoices turn — same shape as ProviderGroup, but with steppers instead of a single tap. */
data class ProductProviderGroup(
    val providerName: String,
    val summary: String,
    val items: List<PickableProduct>,
    val note: String? = null,
)

sealed interface NilaResponse {
    data class Text(val value: String) : NilaResponse
    data class Alarm(val hour: Int, val minute: Int) : NilaResponse
    /** A confirmed request to turn off an alarm: at [hour]:[minute], or the next alarm when [hour] is null. */
    data class DismissAlarm(val hour: Int?, val minute: Int) : NilaResponse
    data class OpenApp(val name: String, val packageName: String, val fallbackUrl: String) : NilaResponse
    data class Navigate(val destination: String) : NilaResponse
    /** The router chose search_groceries: Zepto and Swiggy Instamart are searched and shown side by side. */
    data class GrocerySearch(val query: String) : NilaResponse
    data class RouteCard(
        val destination: String,
        val distanceKm: Double,
        val durationMin: Double,
        val source: String,
    ) : NilaResponse
    data class Choices(val intro: String, val items: List<Choice>, val source: String = "OFFLINE FIXTURE") : NilaResponse
    /** Like Choices, but each item runs real work (a Zepto call, etc.) when tapped instead of a canned status string. */
    data class AsyncChoices(val intro: String, val items: List<AsyncChoice>) : NilaResponse
    /** Results grouped by provider (Zepto, Swiggy Instamart, ...), each collapsible, for comparing multiple MCPs side by side. */
    data class GroupedChoices(val intro: String, val groups: List<ProviderGroup>) : NilaResponse
    /** Like GroupedChoices, but each item is a quantity stepper (search results, cart contents) instead of a single tap. */
    data class ProductGroupChoices(val intro: String, val groups: List<ProductProviderGroup>) : NilaResponse
    data class Confirmation(val title: String, val detail: String, val price: String) : NilaResponse
    data class Remember(val fact: String, val kind: MemoryKind = MemoryKind.Other) : NilaResponse
    /** One step of the live Swiggy order flow, rendered by FoodCards.kt. */
    data class Food(val card: FoodCard) : NilaResponse
    /** The router chose search_food. The launcher starts the live Swiggy flow, or shows the labelled mock if Swiggy isn't connected. */
    data class FoodSearch(
        val query: String,
        val people: Int? = null,
        val budgetRupees: Int? = null,
        val preference: String? = null,
    ) : NilaResponse
    /**
     * A validated Commit call that has not run. [lines] are the call's own values, so the user sees exactly
     * what Confirm authorizes. Pass [token] to NilaEngine.confirm after the user approves; it works once.
     */
    data class NeedsConfirmation(
        val token: String,
        val call: ToolCall,
        val capability: CapabilityDescriptor,
        val lines: List<Pair<String, String>>,
    ) : NilaResponse
}

/** [extraCapabilities] join the built-in tools. Routing, checking and confirmation all use the same registry. */
/** [now] is injectable so tests can fix the time used to pick am or pm. */
class NilaEngine(
    extraCapabilities: List<CapabilityDescriptor> = emptyList(),
    private val now: () -> LocalTime = { LocalTime.now() },
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val percentage = Regex("""(\d+(?:\.\d+)?)\s*%\s*(?:of)?\s*(\d+(?:\.\d+)?)""")
    private val percentFollowUp = Regex("""\s*(?:and|what about|how about|now)?\s*(?:with\s+)?(\d+(?:\.\d+)?)\s*(?:%|percent)\s*[?.!]*\s*""")
    private val alarm = Regex("""alarm\s+(?:(?:for|at|on)\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
    private val remember = Regex("""remember(?: that)?\s+(.+)""")
    // "1:30", "1.30", "1 30" and "9 pm"; then compact "130" or "1330" as hours and minutes.
    private val removeAlarm = Regex("""\b(remove|delete|cancel|turn off|switch off|stop|dismiss|clear|disable)\b.*\balarms?\b""")
    private val clockTime = Regex("""(?<![\d:.])(\d{1,2})(?:[:.\s](\d{2}))?\s*(am|pm)?(?![\d:.])""")
    private val compactTime = Regex("""(?<!\d)(\d{1,2})(\d{2})\s*(am|pm)?(?!\d)""")
    private val smallTalk = Regex(
        """^\s*(hi+|hello|hey|namaste|thanks|thank you|good (morning|afternoon|evening|night)|who are you|what are you|what('?s| is) your name|what can you do|how are you|help)\b""",
    )
    private val apps = mapOf(
        "youtube" to Triple("YouTube", "com.google.android.youtube", "https://youtube.com"),
        "netflix" to Triple("Netflix", "com.netflix.mediaclient", "https://netflix.com"),
        "instagram" to Triple("Instagram", "com.instagram.android", "https://instagram.com"),
        "bgmi" to Triple("BGMI", "com.pubg.imobile", "https://www.battlegroundsmobileindia.com"),
    )

    /** The tools a router may call. FunctionGemma will receive these same schemas. */
    val capabilities = listOf(
        CapabilityDescriptor(
            id = "create_alarm",
            description = "Set an alarm or wake-up time, for example \"wake me up at 6\" or \"alarm for 7:30 pm\". Give the hour as the user said it and meridiem (am or pm) only if they said one.",
            params = listOf(
                Param("hour", ParamType.INT),
                Param("minute", ParamType.INT, required = false),
                Param("meridiem", ParamType.STRING, required = false),
            ),
            permission = PermissionClass.PREPARE,
            androidPermissions = setOf("com.android.alarm.permission.SET_ALARM"),
        ),
        CapabilityDescriptor(
            id = "remove_alarm",
            description = "Turn off, cancel, delete or remove an alarm. Give the time if the user said one; with no time it means their next alarm.",
            params = listOf(
                Param("hour", ParamType.INT, required = false),
                Param("minute", ParamType.INT, required = false),
                Param("meridiem", ParamType.STRING, required = false),
            ),
            // Deleting is a Commit action: it always stops at a confirmation card.
            permission = PermissionClass.COMMIT,
            label = "Turn off an alarm",
            androidPermissions = setOf("com.android.alarm.permission.SET_ALARM"),
        ),
        CapabilityDescriptor(
            id = "open_app",
            description = "Open one of these apps by name: ${apps.keys.joinToString()}",
            params = listOf(Param("app", ParamType.STRING)),
            permission = PermissionClass.READ,
        ),
        CapabilityDescriptor(
            id = "remember_fact",
            description = "Store a fact the user explicitly asked NILA to remember",
            params = listOf(
                Param("fact", ParamType.STRING),
                Param(
                    "kind",
                    ParamType.STRING,
                    required = false,
                    label = "What the fact is about, one of: ${MemoryKind.entries.joinToString { it.name }}. " +
                        "Preference is something they like, Diet is how they eat, Allergy is what they must avoid, " +
                        "Routine is a habit or schedule, Place is a location, Other is anything else.",
                ),
            ),
            permission = PermissionClass.PREPARE,
        ),
        CapabilityDescriptor(
            id = "search_food",
            description = "Order or buy food, or find restaurants and dishes to eat, for example biryani, pizza or dinner.",
            params = listOf(
                Param("query", ParamType.STRING, label = "Dish or restaurant name only, like biryani"),
                Param("preference", ParamType.STRING, required = false, label = "Taste or diet words the user said, like light, spicy or veg"),
            ),
            permission = PermissionClass.READ,
        ),
        CapabilityDescriptor(
            id = "search_groceries",
            description = "Buy groceries or household items from Zepto or Swiggy Instamart, for example milk, eggs, bread, atta or detergent.",
            params = listOf(Param("query", ParamType.STRING, label = "Item and pack size as the user said, like milk 1 litre")),
            permission = PermissionClass.READ,
        ),
        CapabilityDescriptor(
            id = "get_directions",
            description = "Show a map with the route, distance or travel time to a place, for example take me to or how far is.",
            params = listOf(Param("destination", ParamType.STRING, label = "Place name only, like Indiranagar")),
            permission = PermissionClass.READ,
        ),
        CapabilityDescriptor(
            id = "set_timer",
            description = "Start a countdown timer, for example \"timer for 10 minutes\".",
            params = listOf(
                Param("hours", ParamType.INT, required = false),
                Param("minutes", ParamType.INT, required = false),
                Param("seconds", ParamType.INT, required = false),
            ),
            permission = PermissionClass.PREPARE,
            androidPermissions = setOf("com.android.alarm.permission.SET_ALARM"),
            label = "Start a timer",
        ),
        CapabilityDescriptor(
            id = "timer_control",
            description = "Pause, resume or stop the running timer, or tell how much time is left (status).",
            params = listOf(Param("action", ParamType.STRING)),
            permission = PermissionClass.PREPARE,
            label = "Timer",
        ),
        CapabilityDescriptor(
            id = "flashlight",
            description = "Turn the phone's flashlight (torch) on or off.",
            params = listOf(Param("on", ParamType.BOOLEAN)),
            permission = PermissionClass.PREPARE,
            label = "Flashlight",
        ),
        CapabilityDescriptor(
            id = "monthly_reminder",
            description = "A reminder that repeats every month, like paying the electricity bill. Give what to do, the day of the month and the time if said.",
            params = listOf(
                Param("title", ParamType.STRING),
                Param("day", ParamType.INT, required = false),
                Param("hour", ParamType.INT, required = false),
                Param("minute", ParamType.INT, required = false),
                Param("meridiem", ParamType.STRING, required = false),
            ),
            permission = PermissionClass.PREPARE,
            label = "Monthly reminder",
        ),
        CapabilityDescriptor(
            id = "create_event",
            description = "Add an event, meeting or appointment to the calendar. Give the title, the day (today, tomorrow or a weekday) and the time if said.",
            params = listOf(
                Param("title", ParamType.STRING),
                Param("day", ParamType.STRING, required = false),
                Param("hour", ParamType.INT, required = false),
                Param("minute", ParamType.INT, required = false),
                Param("meridiem", ParamType.STRING, required = false),
            ),
            permission = PermissionClass.PREPARE,
            label = "Add to calendar",
        ),
        CapabilityDescriptor(
            id = "add_note",
            description = "Save a note the user dictates, for example \"take a note that the gate code is 4521\".",
            params = listOf(Param("text", ParamType.STRING)),
            permission = PermissionClass.PREPARE,
            label = "Save a note",
        ),
        CapabilityDescriptor(
            id = "open_camera",
            description = "Open the camera to take a photo or record a video.",
            params = listOf(Param("video", ParamType.BOOLEAN, required = false), Param("front", ParamType.BOOLEAN, required = false)),
            permission = PermissionClass.READ,
            label = "Camera",
        ),
        CapabilityDescriptor(
            id = "record_audio",
            description = "Open the phone's voice recorder to record the user's voice or a voice note.",
            params = emptyList(),
            permission = PermissionClass.READ,
            label = "Voice recorder",
        ),
        CapabilityDescriptor(
            id = "call_contact",
            description = "Call a person by name or a phone number. NILA opens the dialer; the user presses call.",
            params = listOf(Param("who", ParamType.STRING)),
            permission = PermissionClass.PREPARE,
            label = "Call",
        ),
    ).plus(moreCapabilities).plus(extraCapabilities).associateBy { it.id }

    /** Timer length read from the user's words, so the model never decides the duration. */
    fun timerArgs(input: String): Map<String, Any>? = timerSeconds(input)?.let { mapOf("seconds" to it) }

    /** Event title, day and time read from the user's words. */
    fun eventArgs(input: String): Map<String, Any> {
        val args = mutableMapOf<String, Any>("title" to eventTitle(input))
        val lower = input.lowercase()
        Regex("""\b(today|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""").find(lower)?.let { args["day"] = it.value }
        // Only a time introduced by "at"/"from" or written with am/pm or a colon counts; "with 2 people" is not a time.
        if (Regex("""\b(?:at|from)\s+\d|\d\s*(?:am|pm)\b|\d[:.]\d{2}""").containsMatchIn(normalize(input))) alarmArgs(input)?.let(args::putAll)
        return args
    }

    private companion object {
        const val MAX_PARTY = 20
        val NUMBER_WORDS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        )
    }

    // Speech recognizers write "6:30 p.m."; fold a.m./p.m. into am/pm so the time is not misread.
    private fun normalize(input: String) = input.trim().lowercase().replace(Regex("""(?<=[\d\s])([ap])\.\s?m\.?"""), "$1m")

    fun respond(input: String): NilaResponse = calculate(input) ?: execute(route(input))

    /** Greetings and questions about NILA itself. These go straight to conversation, never to a tool. */
    /**
     * Only a greeting or a question about NILA, with at most two more words ("hello there", "how are you
     * doing today"). "hey, wake me up at 7" starts like small talk but is a request, so it still reaches the
     * rewrite and FunctionGemma instead of chat only.
     */
    // Only right after the greeting, and never "nila" alone: "hey nila play music" is a request.
    private val GreetingTail = Regex("""^[\s,!.]*(how are you( doing)?( today)?|how('s| is) it going|what'?s up|good (morning|afternoon|evening|night))[\s,!.?]*$""")

    fun isSmallTalk(input: String): Boolean {
        val match = smallTalk.find(input.lowercase()) ?: return false
        // "hello how are you" is all small talk: Gemma picked show_notes for it on the iQOO 15.
        val rest = input.lowercase().substring(match.range.last + 1).let { if (GreetingTail.matches(it)) "" else it }
        return rest.split(Regex("""[^\p{L}\p{N}']+""")).count { it.isNotBlank() } <= 2
    }

    /**
     * Reads the alarm time exactly as the user said it. When a model picks create_alarm, these values
     * replace the model's: FunctionGemma dropped "pm" and ":45" on the iQOO 15.
     */
    /**
     * How many people and the budget, read from the user's own words like the alarm time is: a model
     * invents numbers ("for three" is not a digit it can ground), so these never come from the model.
     */
    fun foodConstraints(input: String, search: NilaResponse.FoodSearch): NilaResponse.FoodSearch {
        val text = normalize(input)
        val budget = Regex("""(?:under|below|within|less than|up ?to|max|budget(?: of)?)\s*(?:rs\.?|₹|inr)?\s*(\d{2,6})""").find(text)
            ?: Regex("""(?:₹|rs\.?\s?)(\d{2,6})""").find(text)
        val party = Regex("""\bfor\s+(\d{1,2}|${NUMBER_WORDS.keys.joinToString("|")})\b(?!\s*(?:am|pm|:))(?:\s+(?:people|persons|of us))?""")
        val people = party.find(text)?.groupValues?.get(1)?.let { it.toIntOrNull() ?: NUMBER_WORDS[it] }
        // Gemma sometimes keeps "for three" or "under 800" in the dish name; the chips carry those now.
        val dish = normalize(search.query).replace(party, " ").let { rest -> budget?.let { rest.replace(it.value, " ") } ?: rest }
            .replace(Regex("""\s+"""), " ").trim()
        return search.copy(
            query = dish.ifBlank { search.query },
            people = people?.takeIf { it in 1..MAX_PARTY },
            budgetRupees = budget?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    fun alarmArgs(input: String): Map<String, Any>? {
        val text = normalize(input)
        val match = clockTime.find(text) ?: compactTime.find(text) ?: return null
        val args = mutableMapOf<String, Any>("hour" to match.groupValues[1].toInt(), "minute" to match.groupValues[2].ifBlank { "0" }.toInt())
        match.groupValues[3].ifEmpty { null }?.let { args["meridiem"] = it }
        return args
    }

    /** Deterministic fast path: blank input and exact arithmetic never go through a router or model. */
    fun calculate(input: String): NilaResponse? {
        val normalized = normalize(input)
        if (normalized.isBlank()) return NilaResponse.Text("Tell me what you want to do.")
        val match = percentage.find(normalized) ?: return null
        val rate = match.groupValues[1].toBigDecimal()
        val amount = match.groupValues[2].toBigDecimal()
        val result = amount.multiply(rate).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
        return NilaResponse.Text("${match.groupValues[1]}% of ${match.groupValues[2]} is ${result.stripTrailingZeros().toPlainString()}")
    }

    /**
     * "and 20%" or "what about 15%" after "18% of 4250": the same amount at the new rate, worked out
     * here, never by a model (Gemma once answered "20% of 425 is 90.9" on the iQOO 15).
     */
    fun calculateFollowUp(input: String, earlier: List<String>): NilaResponse? {
        val rate = percentFollowUp.matchEntire(normalize(input))?.groupValues?.get(1) ?: return null
        // The latest amount in the conversation, newest first: after "and 20%" it is NILA's "20% of 4250 is 850".
        val amount = earlier.firstNotNullOfOrNull { percentage.find(normalize(it))?.groupValues?.get(2) } ?: return null
        return calculate("$rate% of $amount")
    }

    // ponytail: keyword router standing in for FunctionGemma; swap in the model behind the same NilaIntent output.
    fun route(input: String): NilaIntent {
        val normalized = normalize(input)

        // Checked before "alarm for 7": "cancel the alarm for 7" removes, it does not set.
        if (removeAlarm.containsMatchIn(normalized)) {
            return NilaIntent.Call(ToolCall("remove_alarm", alarmArgs(input).orEmpty()))
        }

        // "every month" is a repeating calendar reminder, not an alarm or a one-off event.
        monthlyReminderArgs(input)?.let { return NilaIntent.Call(ToolCall("monthly_reminder", it)) }

        // Everyday phone actions. Each reads its values from the user's words.
        // Checked before "timer": "stop the timer" controls the running one, it does not start a new one.
        timerControl(input)?.let { return NilaIntent.Call(ToolCall("timer_control", mapOf("action" to it))) }
        if (Regex("""\btimer\b""").containsMatchIn(normalized)) return NilaIntent.Call(ToolCall("set_timer", timerArgs(input).orEmpty()))
        if (Regex("""\b(flash ?light|torch)\b""").containsMatchIn(normalized)) return NilaIntent.Call(ToolCall("flashlight", mapOf("on" to torchOn(input))))
        // Voice goes to the voice recorder, checked before the camera: "record my voice" is audio, not video.
        if (Regex("""\brecord\b.*\b(voice|audio|sound)\b|\bvoice (note|recording|memo|recorder)\b|\b(audio|sound) recorder\b""").containsMatchIn(normalized)) {
            return NilaIntent.Call(ToolCall("record_audio", emptyMap()))
        }
        if (Regex("""\b(camera|selfie)\b|\btake an? (photo|picture|pic)\b|\brecord an? video\b""").containsMatchIn(normalized)) {
            return NilaIntent.Call(ToolCall("open_camera", mapOf("video" to wantsVideo(input), "front" to wantsFront(input))))
        }
        if (Regex("""^\s*(please\s+)?((take|make|add|write|save|create)\s+(a\s+)?note|note\s+down)\b""").containsMatchIn(normalized)) {
            return NilaIntent.Call(ToolCall("add_note", noteText(input)?.let { mapOf("text" to it) }.orEmpty()))
        }
        callTarget(input)?.let { return NilaIntent.Call(ToolCall("call_contact", mapOf("who" to it))) }
        moreActionCall(input)?.let { return NilaIntent.Call(it) }
        if (Regex("""\b(event|meeting|appointment|calendar)\b""").containsMatchIn(normalized)) return NilaIntent.Call(ToolCall("create_event", eventArgs(input)))

        // The router only extracts what was said; run() converts and validates the time.
        alarm.find(normalized)?.let { match ->
            val args = mutableMapOf<String, Any>(
                "hour" to match.groupValues[1].toInt(),
                "minute" to match.groupValues[2].ifBlank { "0" }.toInt(),
            )
            match.groupValues[3].ifEmpty { null }?.let { args["meridiem"] = it }
            return NilaIntent.Call(ToolCall("create_alarm", args))
        }

        // Match on the raw text: the user's fact is stored as they said it, not as normalized for parsing.
        remember.find(input.lowercase())?.let {
            val fact = input.substring(input.lowercase().indexOf(it.groupValues[1])).trim()
            return NilaIntent.Call(ToolCall("remember_fact", mapOf("fact" to fact)))
        }

        apps.keys.firstOrNull(normalized::contains)?.let { app ->
            return NilaIntent.Call(ToolCall("open_app", mapOf("app" to app)))
        }
        appToOpen(input)?.let { return NilaIntent.Call(ToolCall("launch_app", mapOf("name" to it))) }

        // Food and directions have no keyword rule: FunctionGemma reads the intent and
        // extracts the dish or place, so "I'm starving" works as well as "find biryani".
        return NilaIntent.Converse(input)
    }

    /**
     * Validates a routed intent at the boundary, then maps it to a response. Never trusts router output.
     * Commit calls stop at NeedsConfirmation and run only through [confirm].
     */
    fun execute(
        intent: NilaIntent,
    ): NilaResponse = when (intent) {
        // No assistant model yet, so conversation falls back to the capability list.
        is NilaIntent.Converse -> NilaResponse.Text(
            "I can calculate, set an alarm, remember a preference, compare mock food options, or open YouTube, Netflix, Instagram and BGMI."
        )
        is NilaIntent.Call -> when (val check = checkCall(intent.call, capabilities)) {
            is CallCheck.Rejected -> NilaResponse.Text("I can't do that. ${check.reason}.")
            is CallCheck.Missing -> NilaResponse.Text("I need the ${check.params.joinToString()} to do that.")
            is CallCheck.Ok ->
                if (check.capability.needsConfirmation) confirmation(check.call, check.capability)
                else run(check.call)
        }
    }

    private fun run(call: ToolCall): NilaResponse {
        val args = call.args
        return when (call.capabilityId) {
            "create_alarm" -> alarmHour(args["hour"] as Int, args["minute"] as Int? ?: 0, args["meridiem"] as String?)
                // "Wake me up at 6" means 6:00.
                ?.takeIf { (args["minute"] as Int? ?: 0) in 0..59 }
                ?.let { NilaResponse.Alarm(it, args["minute"] as Int? ?: 0) }
                ?: NilaResponse.Text("That alarm time is not valid.")
            "remove_alarm" -> removalTime(args)?.let { (hour, minute) -> NilaResponse.DismissAlarm(hour, minute) }
                ?: NilaResponse.Text("That alarm time is not valid.")
            "open_app" -> apps[args["app"]]?.let { NilaResponse.OpenApp(it.first, it.second, it.third) }
                ?: NilaResponse.Text("I can't open that app.")
            "remember_fact" -> NilaResponse.Remember(args["fact"] as String, MemoryKind.from(args["kind"] as? String))
            "search_food" -> NilaResponse.FoodSearch(
                query = args["query"] as String,
                // "chicken" for "chicken biryani" repeats the dish; only a word the dish doesn't already say is a preference.
                preference = (args["preference"] as? String)?.takeIf { it.isNotBlank() && !(args["query"] as String).contains(it, ignoreCase = true) },
            )
            "search_groceries" -> NilaResponse.GrocerySearch(args["query"] as String)
            "get_directions" -> NilaResponse.Navigate(args["destination"] as String)
            "set_timer" -> {
                val hours = args["hours"] as Int? ?: 0
                val minutes = args["minutes"] as Int? ?: 0
                val seconds = args["seconds"] as Int? ?: 0
                val duration = hours.toLong() * 3600 + minutes.toLong() * 60 + seconds
                duration.takeIf { hours >= 0 && minutes >= 0 && seconds >= 0 && it in 1..86_400 }
                    ?.let { Timer(it.toInt()) }
                    ?: NilaResponse.Text("How long should the timer run? Try \"timer for 10 minutes\".")
            }
            "flashlight" -> Torch(args["on"] as Boolean)
            "record_audio" -> VoiceRecorder
            "open_camera" -> Camera(args["video"] as Boolean? ?: false, args["front"] as Boolean? ?: false)
            "timer_control" -> (args["action"] as String).takeIf { it in setOf("pause", "resume", "stop", "status") }?.let { TimerControl(it) }
                ?: NilaResponse.Text("I can pause, resume or stop the timer, or tell you how much time is left.")
            "add_note" -> Note(args["text"] as String)
            "call_contact" -> Call(args["who"] as String)
            "monthly_reminder" -> {
                val day = args["day"] as Int?
                val hour = args["hour"] as Int?
                val minute = args["minute"] as Int? ?: 0
                val time = if (hour == null) LocalTime.of(10, 0)
                    else eventHour(hour, args["meridiem"] as String?)?.takeIf { minute in 0..59 }?.let { LocalTime.of(it, minute) }
                when {
                    day == null -> NilaResponse.Text("Which day of the month? Try \"remind me to pay the electricity bill every month on the 5th\".")
                    day !in 1..31 -> NilaResponse.Text("A month has days 1 to 31. Which day should it be?")
                    time == null -> NilaResponse.Text("That reminder time is not valid.")
                    else -> MonthlyReminder(args["title"] as String, day, time)
                }
            }
            "create_event" -> {
                val hour = args["hour"] as Int?
                val minute = args["minute"] as Int? ?: 0
                val time = hour?.let { h -> eventHour(h, args["meridiem"] as String?)?.takeIf { minute in 0..59 }?.let { LocalTime.of(it, minute) } }
                if (hour != null && time == null) NilaResponse.Text("That event time is not valid.")
                else CalendarEvent(args["title"] as String, eventDate(args["day"] as String? ?: "", today()), time)
            }
            else -> moreRun(call) ?: NilaResponse.Text("I can't do that yet. Nothing was done.")
        }
    }

    // ponytail: pending confirmations live in memory; a process restart drops them, which fails safe.
    private val pending = mutableMapOf<String, ToolCall>()

    /** Runs a pending Commit call once. A reused or unknown token runs nothing. */
    fun confirm(token: String): NilaResponse {
        val call = pending.remove(token) ?: return NilaResponse.Text("This confirmation was already used. Nothing was done.")
        val check = checkCall(call, capabilities)
        return if (check is CallCheck.Ok) run(check.call) else NilaResponse.Text("That action is no longer valid. Nothing was done.")
    }

    private fun confirmation(call: ToolCall, capability: CapabilityDescriptor) = NilaResponse.NeedsConfirmation(
        token = UUID.randomUUID().toString().also { pending[it] = call },
        call = call,
        capability = capability,
        lines = if (capability.id == "remove_alarm") listOf("Turn off" to removalLabel(call.args))
        else capability.params.mapNotNull { param -> call.args[param.name]?.let { param.label to display(param.type, it) } },
    )

    /** 24-hour time to remove, (null, 0) for "the next alarm", or null when the time is invalid. */
    private fun removalTime(args: Map<String, Any>): Pair<Int?, Int>? {
        val hour = args["hour"] as Int? ?: return null to 0
        val minute = args["minute"] as Int? ?: 0
        if (minute !in 0..59) return null
        return alarmHour(hour, minute, args["meridiem"] as String?)?.let { it to minute }
    }

    /** What the confirmation card says will be turned off, using the same time rules as removing it. */
    private fun removalLabel(args: Map<String, Any>): String = when (val time = removalTime(args)) {
        null -> "An invalid time (nothing will be removed)"
        else -> time.first?.let { "The %02d:%02d alarm".format(it, time.second) } ?: "Your next alarm"
    }

    /** Formats a validated argument for people. Paise become rupees in Indian grouping, such as ₹1,23,456.00. */
    private fun display(type: ParamType, value: Any): String = when (type) {
        ParamType.PAISE -> rupees((value as Number).toLong())
        else -> value.toString()
    }

    // Grouped by hand: JVM and Android locale data disagree on Indian grouping. checkCall guarantees paise >= 0.
    private fun rupees(paise: Long): String {
        val whole = (paise / 100).toString()
        val grouped = if (whole.length <= 3) whole
        else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        return "₹$grouped.${(paise % 100).toString().padStart(2, '0')}"
    }

    /** 24-hour value, or null when invalid. With am/pm, only 1-12 is accepted. */
    private fun alarmHour(hour: Int, minute: Int, meridiem: String?): Int? = when {
        // Checked first: nextOccurrence builds a LocalTime, which throws on minute 99 instead of rejecting it.
        minute !in 0..59 -> null
        else -> hourFor(hour, minute, meridiem)
    }

    private fun hourFor(hour: Int, minute: Int, meridiem: String?): Int? = when (meridiem) {
        null -> when (hour) {
            in 1..12 -> nextOccurrence(hour, minute)
            0, in 13..23 -> hour
            else -> null
        }
        "am" -> hour.takeIf { it in 1..12 }?.rem(12)
        "pm" -> hour.takeIf { it in 1..12 }?.let { it % 12 + 12 }
        else -> null
    }

    /** "1:30" with no am or pm means the next 1:30 to come: 13:30 when said at 05:51. */
    private fun nextOccurrence(hour: Int, minute: Int): Int {
        val morning = hour % 12
        return if (LocalTime.of(morning, minute).isAfter(now())) morning else morning + 12
    }

    /** Labelled offline fixture, shown only when Swiggy isn't connected. */
    fun mockFood() = NilaResponse.Choices(
        intro = "I found two mock options that fit the request. Nothing has been ordered.",
        items = listOf(
            Choice("Meghana Foods", "Chicken boneless biryani · 4.6★", "₹239", "24 min"),
            Choice("Mani's Dum Biryani", "Chicken dum biryani · 4.4★", "₹219", "29 min"),
        ),
    )

    fun prepare(choice: Choice) = NilaResponse.Confirmation(
        title = "Review ${choice.title}",
        detail = "${choice.detail} · ${choice.eta}",
        price = choice.price,
    )
}
