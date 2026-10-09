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
    data class Confirmation(val title: String, val detail: String, val price: String) : NilaResponse
    data class Remember(val fact: String) : NilaResponse
}

class NilaEngine {
    private val percentage = Regex("""(\d+(?:\.\d+)?)\s*%\s*(?:of)?\s*(\d+(?:\.\d+)?)""")
    private val alarm = Regex("""alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""")
    private val apps = mapOf(
        "youtube" to Triple("YouTube", "com.google.android.youtube", "https://youtube.com"),
        "netflix" to Triple("Netflix", "com.netflix.mediaclient", "https://netflix.com"),
        "instagram" to Triple("Instagram", "com.instagram.android", "https://instagram.com"),
        "bgmi" to Triple("BGMI", "com.pubg.imobile", "https://www.battlegroundsmobileindia.com"),
    )

    fun respond(input: String): NilaResponse {
        // Speech recognizers write "6:30 p.m."; fold a.m./p.m. into am/pm so the time is not misread.
        val normalized = input.trim().lowercase().replace(Regex("""(?<=[\d\s])([ap])\.\s?m\.?"""), "$1m")
        if (normalized.isBlank()) return NilaResponse.Text("Tell me what you want to do.")

        percentage.find(normalized)?.let { match ->
            val rate = match.groupValues[1].toBigDecimal()
            val amount = match.groupValues[2].toBigDecimal()
            val result = amount.multiply(rate).divide(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
            return NilaResponse.Text("${match.groupValues[1]}% of ${match.groupValues[2]} is ${result.stripTrailingZeros().toPlainString()}")
        }

        alarm.find(normalized)?.let { match ->
            var hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].ifBlank { "0" }.toInt()
            val meridiem = match.groupValues[3]
            if (hour !in 0..23 || minute !in 0..59 || (meridiem.isNotEmpty() && hour !in 1..12)) {
                return NilaResponse.Text("That alarm time is not valid.")
            }
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            return NilaResponse.Alarm(hour, minute)
        }

        Regex("""remember(?: that)?\s+(.+)""").find(normalized)?.let {
            val originalFact = input.substring(input.lowercase().indexOf(it.groupValues[1]))
            return NilaResponse.Remember(originalFact.trim())
        }

        apps.entries.firstOrNull { (keyword) -> normalized.contains(keyword) }?.value?.let { app ->
            return NilaResponse.OpenApp(app.first, app.second, app.third)
        }

        if (listOf("food", "biryani", "dinner", "lunch").any(normalized::contains)) {
            return NilaResponse.Choices(
                intro = "I found two mock options that fit the request. Nothing has been ordered.",
                items = listOf(
                    Choice("Meghana Foods", "Chicken boneless biryani · 4.6★", "₹239", "24 min"),
                    Choice("Mani's Dum Biryani", "Chicken dum biryani · 4.4★", "₹219", "29 min"),
                ),
            )
        }

        return NilaResponse.Text(
            "I can calculate, set an alarm, remember a preference, compare mock food options, or open YouTube, Netflix, Instagram and BGMI."
        )
    }

    fun prepare(choice: Choice) = NilaResponse.Confirmation(
        title = "Review ${choice.title}",
        detail = "${choice.detail} · ${choice.eta}",
        price = choice.price,
    )
}

