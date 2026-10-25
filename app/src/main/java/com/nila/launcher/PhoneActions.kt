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
data class Camera(val video: Boolean, val front: Boolean = false) : NilaResponse
/** The phone's own voice recorder, opened ready to record. */
data object VoiceRecorder : NilaResponse
/** Pause, resume, stop or ask about NILA's own timer. */
data class TimerControl(val action: String) : NilaResponse
/** A note saved in NILA; the card offers to copy it into the phone's notes app too. */
data class NoteSaved(val text: String) : NilaResponse
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

/** "take a selfie" or "front camera": the camera should open facing the user. */
fun wantsFront(text: String) = Regex("""\b(selfie|front camera|front-facing)\b""").containsMatchIn(text.lowercase())

/** "pause the timer", "stop timer", "how much time is left" → the action; null when not about a running timer. */
fun timerControl(text: String): String? {
    val lower = text.lowercase()
    return when {
        // Status needs the timer named, or "time ... left": "how much time to Whitefield" is a directions
        // question and "how much battery is left" is the battery.
        Regex("""\btimer\b.*\b(left|remaining|status)\b|\b(left|remaining|status|check)\b.*\btimer\b|\bhow (?:much|long)\s+time\b.*\b(left|remaining)\b|\bhow much longer\b""").containsMatchIn(lower) -> "status"
        !Regex("""\btimer\b""").containsMatchIn(lower) -> null
        Regex("""\b(pause|hold|freeze)\b""").containsMatchIn(lower) -> "pause"
        Regex("""\b(resume|continue|unpause|restart)\b""").containsMatchIn(lower) -> "resume"
        Regex("""\b(stop|cancel|end|dismiss|off|clear|reset)\b""").containsMatchIn(lower) -> "stop"
        else -> null
    }
}

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
fun callTarget(text: String): String? = callLead.find(text)?.let { text.substring(it.range.last + 1).trim().trimEnd('.', '!', '?') }
    // "ring mode" and "ring tone" are about sound, not calling someone named "mode" (seen on the iQOO 15).
    ?.takeIf { it.isNotBlank() && !Regex("""^(mode|tone|volume)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

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

enum class DismissPlan { DISMISS_NEXT, SHOW_LIST, NOTHING_SET }

/**
 * Decides how to turn off an alarm when Android shows apps only the next enabled one. A specific time is
 * dismissed only when it IS that next alarm; anything else opens the alarm list for the user to choose,
 * because vivo's Clock removed other alarms when asked for a time it did not have.
 */
fun dismissPlan(hour: Int?, minute: Int, nextHour: Int?, nextMinute: Int?): DismissPlan = when {
    nextHour == null -> if (hour == null) DismissPlan.NOTHING_SET else DismissPlan.SHOW_LIST
    hour == null -> DismissPlan.DISMISS_NEXT
    hour == nextHour && minute == nextMinute -> DismissPlan.DISMISS_NEXT
    else -> DismissPlan.SHOW_LIST
}

/** A reminder that repeats every month on [day] at [time]; a day after the 28th means the month's last day. */
data class MonthlyReminder(val title: String, val day: Int, val time: LocalTime) : NilaResponse {
    /**
     * The calendar's repeat rule: the same date each month. For the 29th to 31st, the latest of that date
     * and the two before it, so a bill due on the 30th is on the 30th, and on the 28th in February.
     */
    val rule get() = if (day > LAST_SAFE_DAY) "FREQ=MONTHLY;BYMONTHDAY=${day - 2},${day - 1},$day;BYSETPOS=-1" else "FREQ=MONTHLY;BYMONTHDAY=$day"
}

private const val LAST_SAFE_DAY = 28
private val monthlyWords = Regex("""\b(every month|each month|monthly|every month's)\b""")
private val monthDay = Regex("""\b(?:on\s+(?:the\s+)?)?(\d{1,2})(?:st|nd|rd|th)\b|\bon\s+(?:the\s+|day\s+)?(\d{1,2})\b(?!\s*(?:am|pm|:))""")
private val reminderTime = Regex("""\bat\s+(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?\b""")
private val reminderLead = Regex("""^\s*(please\s+)?(monthly reminder|remind me|set a (monthly )?reminder|add a (monthly )?reminder|reminder)\s*(to|about|for|that)?\s*""", RegexOption.IGNORE_CASE)

/**
 * "remind me to pay the electricity bill every month on the 5th at 9 am" → title, day, time. Null when it
 * is not a monthly reminder. The day and time come only from the user's words.
 */
fun monthlyReminderArgs(text: String): Map<String, Any>? {
    val lower = text.lowercase()
    if (!monthlyWords.containsMatchIn(lower)) return null
    if (!Regex("""\b(remind|reminder)\b""").containsMatchIn(lower)) return null
    val args = mutableMapOf<String, Any>()
    monthDay.find(lower)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()?.let { args["day"] = it } }
    reminderTime.find(lower)?.let { m ->
        args["hour"] = m.groupValues[1].toInt()
        m.groupValues[2].toIntOrNull()?.let { args["minute"] = it }
        m.groupValues[3].takeIf { it.isNotEmpty() }?.let { args["meridiem"] = it }
    }
    val title = reminderLead.replace(lower, "")
        .replace(monthlyWords, " ").replace(monthDay, " ").replace(reminderTime, " ")
        .replace(Regex("""\b(of|on|the)\s*$"""), "").replace(Regex("""\s+"""), " ").trim().trimEnd('.', ',')
    args["title"] = title.ifBlank { "Reminder" }.replaceFirstChar { it.uppercase() }
    return args
}

/** "the 5th", "the 21st", "the last day". */
fun dayLabel(reminder: MonthlyReminder): String = reminder.day.let { d ->
    "the " + d.toString() + when {
        d % 100 in 11..13 -> "th"
        d % 10 == 1 -> "st"
        d % 10 == 2 -> "nd"
        d % 10 == 3 -> "rd"
        else -> "th"
    }
}

/** When the reminder first fires: this month if its day and time are still ahead, else next month. */
fun firstReminder(reminder: MonthlyReminder, now: java.time.LocalDateTime): java.time.LocalDateTime {
    fun inMonth(month: java.time.YearMonth) =
        // The 30th in February is the 28th (or 29th): the month's last day, never a skipped month.
        month.atDay(minOf(reminder.day, month.lengthOfMonth())).atTime(reminder.time)
    val thisMonth = inMonth(java.time.YearMonth.from(now))
    return if (thisMonth.isAfter(now)) thisMonth else inMonth(java.time.YearMonth.from(now).plusMonths(1))
}
