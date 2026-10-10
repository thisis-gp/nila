package com.nila.launcher

import java.math.BigDecimal
import java.math.RoundingMode
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

sealed interface NilaResponse {
    data class Text(val value: String) : NilaResponse
    data class Alarm(val hour: Int, val minute: Int) : NilaResponse
    data class OpenApp(val name: String, val packageName: String, val fallbackUrl: String) : NilaResponse
    data class Choices(val intro: String, val items: List<Choice>) : NilaResponse
    /** Like Choices, but each item runs real work (a Zepto call, etc.) when tapped instead of a canned status string. */
    data class AsyncChoices(val intro: String, val items: List<AsyncChoice>) : NilaResponse
    /** Results grouped by provider (Zepto, Swiggy Instamart, ...), each collapsible, for comparing multiple MCPs side by side. */
    data class GroupedChoices(val intro: String, val groups: List<ProviderGroup>) : NilaResponse
    data class Confirmation(val title: String, val detail: String, val price: String) : NilaResponse
    data class Remember(val fact: String) : NilaResponse
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
) {
    private val percentage = Regex("""(\d+(?:\.\d+)?)\s*%\s*(?:of)?\s*(\d+(?:\.\d+)?)""")
    private val alarm = Regex("""alarm\s+(?:(?:for|at|on)\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
    private val remember = Regex("""remember(?: that)?\s+(.+)""")
    // "1:30", "1.30", "1 30" and "9 pm"; then compact "130" or "1330" as hours and minutes.
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
            id = "open_app",
            description = "Open one of these apps by name: ${apps.keys.joinToString()}",
            params = listOf(Param("app", ParamType.STRING)),
            permission = PermissionClass.READ,
        ),
        CapabilityDescriptor(
            id = "remember_fact",
            description = "Store a fact the user explicitly asked NILA to remember",
            params = listOf(Param("fact", ParamType.STRING)),
            permission = PermissionClass.PREPARE,
        ),
        CapabilityDescriptor(
            id = "search_food",
            description = "Find restaurants or dishes to eat, only when the user asks for food, a meal, a dish or says they are hungry",
            params = listOf(Param("query", ParamType.STRING)),
            permission = PermissionClass.READ,
        ),
    ).plus(extraCapabilities).associateBy { it.id }

    // Speech recognizers write "6:30 p.m."; fold a.m./p.m. into am/pm so the time is not misread.
    private fun normalize(input: String) = input.trim().lowercase().replace(Regex("""(?<=[\d\s])([ap])\.\s?m\.?"""), "$1m")

    fun respond(input: String): NilaResponse = calculate(input) ?: execute(route(input))

    /** Greetings and questions about NILA itself. These go straight to conversation, never to a tool. */
    fun isSmallTalk(input: String) = smallTalk.containsMatchIn(input.lowercase())

    /**
     * Reads the alarm time exactly as the user said it. When a model picks create_alarm, these values
     * replace the model's: FunctionGemma dropped "pm" and ":45" on the iQOO 15.
     */
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

    // ponytail: keyword router standing in for FunctionGemma; swap in the model behind the same NilaIntent output.
    fun route(input: String): NilaIntent {
        val normalized = normalize(input)

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

        if (listOf("food", "biryani", "dinner", "lunch").any(normalized::contains)) {
            return NilaIntent.Call(ToolCall("search_food", mapOf("query" to input.trim())))
        }

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
            "open_app" -> apps[args["app"]]?.let { NilaResponse.OpenApp(it.first, it.second, it.third) }
                ?: NilaResponse.Text("I can't open that app.")
            "remember_fact" -> NilaResponse.Remember(args["fact"] as String)
            "search_food" -> NilaResponse.Choices(
                intro = "I found two mock options that fit the request. Nothing has been ordered.",
                items = listOf(
                    Choice("Meghana Foods", "Chicken boneless biryani · 4.6★", "₹239", "24 min"),
                    Choice("Mani's Dum Biryani", "Chicken dum biryani · 4.4★", "₹219", "29 min"),
                ),
            )
            else -> NilaResponse.Text("I can't do that yet. Nothing was done.")
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
        lines = capability.params.mapNotNull { param -> call.args[param.name]?.let { param.label to display(param.type, it) } },
    )

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
    private fun alarmHour(hour: Int, minute: Int, meridiem: String?): Int? = when (meridiem) {
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

    fun prepare(choice: Choice) = NilaResponse.Confirmation(
        title = "Review ${choice.title}",
        detail = "${choice.detail} · ${choice.eta}",
        price = choice.price,
    )
}
