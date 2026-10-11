package com.nila.launcher

import java.time.LocalDate
import org.json.JSONObject

/**
 * What a message means for My NILA, as Gemma reads it: in any wording or language, with names as well as
 * "my wife". The fast word patterns still go first; this is for everything they miss.
 */
enum class MyNilaType { Fact, HealthEvent, HealthQuestion, Photos, Other }

data class MyNilaRead(val type: MyNilaType, val date: LocalDate?)

/** Gemma's one-line JSON as a [MyNilaRead]; anything unreadable is [MyNilaType.Other], so NILA carries on as before. */
internal fun parseMyNilaRead(raw: String): MyNilaRead {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    val o = if (start in 0 until end) runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() else null
    val type = when (o?.optString("type")?.trim()?.lowercase()) {
        "fact" -> MyNilaType.Fact
        "health_event" -> MyNilaType.HealthEvent
        "health_question" -> MyNilaType.HealthQuestion
        "photos" -> MyNilaType.Photos
        else -> MyNilaType.Other
    }
    val date = o?.optString("date")?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return MyNilaRead(type, date)
}

private val DateWords = Regex(
    """\d|\b(today|yesterday|tomorrow|week|month|year|ago|monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
        """jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|kal|parso|aaj|innale|innu|innale)\w*\b|[ऀ-ॿഀ-ൿ]""",
)

/** A date from Gemma is used only when the message itself names a day, so "show my photos" never gets a guessed one. */
internal fun namesADay(text: String): Boolean = DateWords.containsMatchIn(text.lowercase())

/** "Wed 18 Dec 2024". */
internal fun dayLabelOf(date: LocalDate): String = "%s %d %s %d".format(
    date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
    date.dayOfMonth,
    date.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
    date.year,
)

/** The health records as lines Gemma answers from, newest first, and nothing else. */
internal fun healthContext(records: List<HealthRecord>): String =
    newestFirst(records).take(HEALTH_CONTEXT_LIMIT).joinToString("\n") { r ->
        listOfNotNull(
            r.date.toString(),
            r.kind.label,
            r.title,
            r.place.takeIf(String::isNotBlank),
            r.followUp?.let { "follow-up $it" },
            r.notes.lineSequence().filterNot { it.startsWith("Read by NILA") }.joinToString(" ").takeIf(String::isNotBlank),
        ).joinToString(" | ")
    }

private const val HEALTH_CONTEXT_LIMIT = 20
