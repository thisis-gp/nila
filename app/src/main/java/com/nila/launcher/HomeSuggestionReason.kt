package com.nila.launcher

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ToldOn = DateTimeFormatter.ofPattern("d MMM")

/** Why the home suggestion is shown: the user's own note it came from. Null when there is no such note, so nothing is invented. */
internal fun suggestionReason(facts: List<MemoryFact>): String? {
    val liked = facts.filter { it.kind == MemoryKind.Preference && it.about == ME }.maxByOrNull { it.savedAt } ?: return null
    val day = Instant.ofEpochMilli(liked.savedAt).atZone(ZoneId.systemDefault()).format(ToldOn)
    return "You told me on $day: ${liked.text}"
}
