package com.nila.launcher

import java.math.BigDecimal
import java.math.RoundingMode

data class Choice(
    val title: String,
    val detail: String,
    val price: String,
    val eta: String,
)

sealed interface NilaResponse {
    data class Text(val value: String) : NilaResponse
    data class Alarm(val hour: Int, val minute: Int) : NilaResponse
    data class OpenApp(val name: String, val packageName: String, val fallbackUrl: String) : NilaResponse
    data class Choices(val intro: String, val items: List<Choice>) : NilaResponse
    data class Remember(val fact: String) : NilaResponse
}

class NilaEngine {
    private val percentage = Regex("""(\d+(?:\.\d+)?)\s*%\s*(?:of)?\s*(\d+(?:\.\d+)?)""")
    private val alarm = Regex("""alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
    private val remember = Regex("""remember(?: that)?\s+(.+)""")
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
            description = "Set an alarm. Hour is 0-23.",
            params = listOf(Param("hour", ParamType.INT), Param("minute", ParamType.INT)),
            permission = PermissionClass.PREPARE,
            androidPermissions = setOf("com.android.alarm.permission.SET_ALARM"),
        ),
        CapabilityDescriptor(
            id = "open_app",
            description = "Open an immersive app: ${apps.keys.joinToString()}",
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
            description = "Find and compare food options",
            params = listOf(Param("query", ParamType.STRING)),
            permission = PermissionClass.READ,
        ),
    ).associateBy { it.id }

    fun respond(input: String): NilaResponse {
        val normalized = input.trim().lowercase()
        if (normalized.isBlank()) return NilaResponse.Text("Tell me what you want to do.")

        // Deterministic fast path: exact arithmetic never goes through a router.
        percentage.find(normalized)?.let { match ->
            val rate = match.groupValues[1].toBigDecimal()
            val amount = match.groupValues[2].toBigDecimal()
            val result = amount.multiply(rate).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
            return NilaResponse.Text("${match.groupValues[1]}% of ${match.groupValues[2]} is ${result.stripTrailingZeros().toPlainString()}")
        }

        return execute(route(input))
    }

    // ponytail: keyword router standing in for FunctionGemma; swap in the model behind the same NilaIntent output.
    fun route(input: String): NilaIntent {
        val normalized = input.trim().lowercase()

        alarm.find(normalized)?.let { match ->
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifBlank { "0" }.toInt()
            when (match.groupValues[3]) {
                "pm" -> if (hour in 1..11) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            return NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to hour, "minute" to minute)))
        }

        remember.find(normalized)?.let {
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

    /** Validates a routed intent at the boundary, then maps it to a response. Never trusts router output. */
    fun execute(intent: NilaIntent): NilaResponse = when (intent) {
        // No assistant model yet, so conversation falls back to the capability list.
        is NilaIntent.Converse -> NilaResponse.Text(
            "I can calculate, set an alarm, remember a preference, compare mock food options, or open YouTube, Netflix, Instagram and BGMI."
        )
        is NilaIntent.Call -> when (val check = checkCall(intent.call, capabilities)) {
            is CallCheck.Rejected -> NilaResponse.Text("I can't do that. ${check.reason}.")
            is CallCheck.Missing -> NilaResponse.Text("I need the ${check.params.joinToString()} to do that.")
            is CallCheck.Ok -> run(check.call)
        }
    }

    private fun run(call: ToolCall): NilaResponse {
        val args = call.args
        return when (call.capabilityId) {
            "create_alarm" -> {
                val hour = args["hour"] as Int
                val minute = args["minute"] as Int
                if (hour in 0..23 && minute in 0..59) NilaResponse.Alarm(hour, minute)
                else NilaResponse.Text("That alarm time is not valid.")
            }
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
            else -> error("Capability ${call.capabilityId} is registered but has no executor")
        }
    }
}
