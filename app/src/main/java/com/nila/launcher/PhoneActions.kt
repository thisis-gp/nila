package com.nila.launcher

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

// Everyday phone actions: timer, flashlight, calendar, notes, camera and calls. Parsing here is plain
// functions over the user's words, so the exact values never come from a model.

data class Timer(val seconds: Int) : NilaResponse
data class Torch(val on: Boolean) : NilaResponse
/** [time] is null for an all-day event. */
data class CalendarEvent(val title: String, val date: LocalDate, val time: LocalTime?) : NilaResponse
data class Note(val text: String) : NilaResponse
data class Camera(val video: Boolean) : NilaResponse
/** [who] is a phone number or a contact name, as the user said it. */
data class Call(val who: String) : NilaResponse

private val durationPart = Regex("""(\d+)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""")

/** "10 minutes", "1 hour 30 minutes", "90 seconds", "5 min" → seconds, or null when no duration was said. */
fun timerSeconds(text: String): Int? {
    val parts = durationPart.findAll(text.lowercase()).toList()
    if (parts.isEmpty()) return null
    var total = 0L
    for (match in parts) {
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2].first()) {
            'h' -> 3600L
            'm' -> 60L
            else -> 1L
        }
        if (value > (86_400L - total) / multiplier) return null
        total += value * multiplier
    }
    return total.toInt()
}

/** "off", "disable" or "stop" anywhere means off; otherwise on. */
fun torchOn(text: String) = !Regex("""\b(off|disable|stop|switch off|turn off)\b""").containsMatchIn(text.lowercase())

/** True when the user asked for a video rather than a photo. */
fun wantsVideo(text: String) = Regex("""\b(video|record)\b""").containsMatchIn(text.lowercase())

private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase() }

/** "today", "tomorrow" or a weekday name (the next one, never today) → date. Defaults to today. */
fun eventDate(text: String, today: LocalDate): LocalDate {
    val lower = text.lowercase()
    if (Regex("""\btomorrow\b""").containsMatchIn(lower)) return today.plusDays(1)
    weekdays.entries.firstOrNull { (name) -> Regex("""\b$name\b""").containsMatchIn(lower) }?.let { (_, day) ->
        return today.with(TemporalAdjusters.next(day))
    }
    return today
}

/**
 * Event hour without am/pm uses office hours: 1 to 7 means afternoon, 8 to 11 morning. "Meeting at 3" is
 * almost never at 3 at night.
 */
fun eventHour(hour: Int, meridiem: String?): Int? = when (meridiem) {
    "am" -> hour.takeIf { it in 1..12 }?.rem(12)
    "pm" -> hour.takeIf { it in 1..12 }?.let { it % 12 + 12 }
    else -> when (hour) {
        in 1..7 -> hour + 12
        in 8..23, 0 -> hour
        else -> null
    }
}

private val eventLead = Regex(
    """^\s*(please\s+)?(add|create|schedule|set up|put|book|make)\s+(an?\s+|the\s+|my\s+)?(calendar\s+)?(event|meeting|appointment)?\s*(for|called|named|about)?\s*""",
)
private val eventNoise = Regex(
    """\b(to|in|on|into)\s+(my\s+)?calendar\b|\b(today|tomorrow|on\s+\w+day|\w+day)\b|\b(at|from)\s+\d{1,2}([:.]\d{2})?\s*(am|pm)?\b|\b\d{1,2}([:.]\d{2})?\s*(am|pm)\b""",
)

/** "schedule a meeting with Arjun tomorrow at 3 pm" → "Meeting with Arjun". Falls back to "Event". */
fun eventTitle(text: String): String {
    val lower = text.lowercase()
    val keepNoun = Regex("""\b(meeting|appointment)\b""").find(lower)?.value
    var title = eventNoise.replace(eventLead.replace(lower, ""), " ").replace(Regex("""\s+"""), " ").trim()
    if (keepNoun != null && keepNoun !in title) title = "$keepNoun $title".trim()
    title = title.removeSuffix(" with").trim()
    return title.ifBlank { "Event" }.replaceFirstChar { it.uppercase() }
}

private val noteLead = Regex("""^\s*(please\s+)?((take|make|add|write|save|create)\s+(a\s+)?note|note\s+down|note)\s*(that|saying|:|-)?\s*""", RegexOption.IGNORE_CASE)

/** The note's text as the user wrote it, without "take a note that". Null when there is nothing to save. */
fun noteText(text: String): String? = noteLead.find(text)?.let { text.substring(it.range.last + 1).trim() }?.takeIf { it.isNotBlank() }

private val callLead = Regex("""^\s*(please\s+)?(call|phone|dial|ring)\s+(to\s+)?""", RegexOption.IGNORE_CASE)

/** "call Mom" → "Mom"; "dial 98450 12345" → "98450 12345". Null when no one was named. */
fun callTarget(text: String): String? = callLead.find(text)?.let { text.substring(it.range.last + 1).trim().trimEnd('.', '!', '?') }?.takeIf { it.isNotBlank() }

/** Digits of a phone number, or null when [who] is a name. */
fun phoneNumber(who: String): String? = who.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 3 }

/** "Sat 11 Oct, 15:00" or "Sat 11 Oct, all day". */
fun eventWhen(date: LocalDate, time: LocalTime?): String {
    val day = "%s %d %s".format(
        date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
        date.dayOfMonth,
        date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
    )
    return "$day, " + (time?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "all day")
}
