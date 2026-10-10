package com.nila.launcher

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** What a remembered fact is about. The model picks the kind when it saves a fact; NILA never guesses it from keywords. */
enum class MemoryKind(val label: String) {
    Preference("Likes"),
    Diet("Diet"),
    Allergy("Allergy"),
    Routine("Routine"),
    Place("Place"),
    Other("Note");

    companion object {
        /** The kind a model named, or Other when it named none or one NILA doesn't know. */
        fun from(raw: String?): MemoryKind = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Other
    }
}

/** One thing the user asked NILA to remember. Stays on the phone. */
data class MemoryFact(val id: String, val text: String, val kind: MemoryKind, val savedAt: Long)

internal fun newFact(text: String, kind: MemoryKind, savedAt: Long) = MemoryFact(UUID.randomUUID().toString(), text.trim(), kind, savedAt)

/** Adds [fact] unless it is blank or the same text (ignoring case, spacing and a closing full stop) is already saved. */
internal fun withFact(facts: List<MemoryFact>, fact: MemoryFact): List<MemoryFact> =
    if (fact.text.isBlank() || facts.any { sameText(it.text, fact.text) }) facts else facts + fact

/** The single fact older builds saved joins the list as an Other fact. */
internal fun migrated(facts: List<MemoryFact>, legacy: String?, savedAt: Long): List<MemoryFact> =
    legacy?.takeIf { it.isNotBlank() }?.let { withFact(facts, newFact(it, MemoryKind.Other, savedAt)) } ?: facts

private fun sameText(a: String, b: String) = comparable(a) == comparable(b)

private fun comparable(text: String) = text.trim().lowercase().replace(Regex("""\s+"""), " ").trimEnd('.', '!')

/** The answer to "what do you remember": every fact with its kind, or a plain "nothing yet". */
internal fun memorySummary(facts: List<MemoryFact>): String =
    if (facts.isEmpty()) "You have not asked me to remember anything yet."
    else "You asked me to remember:\n" + facts.joinToString("\n") { "• ${it.text} (${it.kind.label.lowercase()})" }

/**
 * The facts, as a JSON list in the `nila_memory` preferences. Reads migrate the old single `fact` key once.
 * ponytail: SharedPreferences holds a few dozen short facts fine; move to ObjectBox with the rest of memory when it lands.
 */
internal class NilaMemory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun facts(): List<MemoryFact> {
        val stored = prefs.getString(FACTS, null)?.let(::decode).orEmpty()
        val legacy = prefs.getString(LEGACY_FACT, null) ?: return stored
        return migrated(stored, legacy, System.currentTimeMillis()).also(::write)
    }

    fun save(text: String, kind: MemoryKind): List<MemoryFact> =
        withFact(facts(), newFact(text, kind, System.currentTimeMillis())).also(::write)

    fun delete(id: String): List<MemoryFact> = facts().filterNot { it.id == id }.also(::write)

    fun clear(): List<MemoryFact> = emptyList<MemoryFact>().also(::write)

    private fun write(facts: List<MemoryFact>) {
        prefs.edit().putString(FACTS, encode(facts)).remove(LEGACY_FACT).apply()
    }

    private fun encode(facts: List<MemoryFact>): String = JSONArray().apply {
        facts.forEach { fact ->
            put(JSONObject().put("id", fact.id).put("text", fact.text).put("kind", fact.kind.name).put("savedAt", fact.savedAt))
        }
    }.toString()

    /** A malformed entry is skipped, never fatal: memory is a convenience, not a dependency. */
    private fun decode(raw: String): List<MemoryFact> {
        val list = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").ifBlank { return@mapNotNull null }
            MemoryFact(o.optString("id").ifBlank { UUID.randomUUID().toString() }, text, MemoryKind.from(o.optString("kind")), o.optLong("savedAt"))
        }
    }

    private companion object {
        const val PREFS = "nila_memory"
        const val FACTS = "facts"
        const val LEGACY_FACT = "fact"
    }
}

// ---- How saved facts shape results -------------------------------------------------
// These read the words of the user's own facts. The model already chose each fact's kind;
// nothing here decides what a request means.

private const val MIN_WORD_LENGTH = 3
/** A fact with two or more subject words matches only when two of them are in the name: "filter coffee" is not "cold coffee". */
private const val STRONG_MATCH_WORDS = 2
private const val SUGGESTION_START_HOUR = 5
private const val SUGGESTION_END_HOUR = 11
internal const val CHAT_FACT_LIMIT = 5

private val DayWords: Map<String, Set<DayOfWeek>> =
    DayOfWeek.entries.associate { it.name.lowercase() to setOf(it) } +
        mapOf(
            "weekday" to setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "weekend" to setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        )
private val PartWords: Map<String, PartOfDay> = PartOfDay.entries.associateBy { it.name.lowercase() }

/** Words that frame a fact rather than name its subject: "I really like", "allergic to", "on Fridays". */
private val FramingWords = setOf(
    "the", "and", "with", "for", "from", "into", "this", "that", "are", "was", "has", "have", "had", "not",
    "like", "likes", "liked", "love", "loves", "loved", "enjoy", "enjoys", "prefer", "prefers", "preferred",
    "favourite", "favorite", "always", "usually", "often", "really", "very", "much", "lot", "some", "any",
    "allergic", "allergy", "allergies", "intolerant", "intolerance", "avoid", "can't", "cannot", "eat", "eats",
    "drink", "drinks", "eating", "drinking", "having", "order", "only", "every", "each", "day", "days", "user", "i'm",
    "who", "when", "food", "foods", "breakfast", "lunch", "dinner", "meal", "meals", "snack", "snacks",
) + DayWords.keys.flatMap { listOf(it, "${it}s") } + PartWords.keys.flatMap { listOf(it, "${it}s") }

private fun words(text: String) = text.lowercase().split(Regex("""[^\p{L}\p{N}']+""")).filter(String::isNotBlank)

/** The words that say what a fact is about: "I really like filter coffee in the morning" gives [filter, coffee]. */
internal fun factWords(text: String): List<String> =
    words(text).filter { it.length >= MIN_WORD_LENGTH && it !in FramingWords }.distinct()

/** Whole-word match that forgives a plural on either side: "peanuts" finds "peanut chikki". */
private fun mentions(text: String, word: String): Boolean {
    val stem = Regex.escape(if (word.length > MIN_WORD_LENGTH + 1) word.removeSuffix("s") else word)
    return Regex("""\b$stem(s|es)?\b""").containsMatchIn(text.lowercase())
}

/** "You like filter coffee" when [name] is what a Preference fact is about; null when no Preference fits. */
internal fun memoryBoost(name: String, facts: List<MemoryFact>): String? =
    facts.filter { it.kind == MemoryKind.Preference }.firstNotNullOfOrNull { fact ->
        val subject = factWords(fact.text).ifEmpty { return@firstNotNullOfOrNull null }
        val matched = subject.filter { mentions(name, it) }
        matched.takeIf { it.size >= minOf(STRONG_MATCH_WORDS, subject.size) }?.let { "You like ${it.joinToString(" ")}" }
    }

/** True when any word of a Preference fact is in [name]; groceries are already narrowed by the search word. */
internal fun sharesPreferenceWord(name: String, facts: List<MemoryFact>): Boolean =
    facts.filter { it.kind == MemoryKind.Preference }.flatMap { factWords(it.text) }.any { mentions(name, it) }

/** "check: peanuts" when [text] names an allergen from an Allergy fact; null otherwise. */
internal fun allergyCheck(text: String, facts: List<MemoryFact>): String? =
    facts.filter { it.kind == MemoryKind.Allergy }.flatMap { factWords(it.text) }.firstOrNull { mentions(text, it) }?.let { "check: $it" }

/** True when a fact's own day and time words, if it has any, include [now]: "on Tuesdays", "weekday mornings". */
internal fun appliesAt(text: String, now: LocalDateTime): Boolean {
    val said = words(text).map { it.removeSuffix("s") }
    val days = said.flatMap { DayWords[it].orEmpty() }.toSet()
    val parts = said.mapNotNull { PartWords[it] }.toSet()
    return (days.isEmpty() || now.dayOfWeek in days) && (parts.isEmpty() || partOfDay(now.hour) in parts)
}

private val VegWord = Regex("""(?<!non[- ])\b(veg|vegetarian|vegan)\b""")

/** True when a Diet fact says veg and its day or time, if it names one, is now. */
internal fun wantsVeg(facts: List<MemoryFact>, now: LocalDateTime): Boolean =
    facts.any { it.kind == MemoryKind.Diet && VegWord.containsMatchIn(it.text.lowercase()) && appliesAt(it.text, now) }

/** What memory changes about one food search, worked out once when the search runs. */
internal data class FoodMemory(val facts: List<MemoryFact>, val vegPreferred: Boolean) {
    companion object {
        val None = FoodMemory(emptyList(), vegPreferred = false)
    }
}

internal fun foodMemory(facts: List<MemoryFact>, now: LocalDateTime) = FoodMemory(facts, wantsVeg(facts, now))

/** One gentle nudge from memory: what the chip or pill says, and the request it sends when tapped. */
internal data class MemorySuggestion(val label: String, val request: String)

/** In the morning, the newest Preference as "Filter coffee for breakfast?"; null at other times or with nothing liked. */
internal fun morningSuggestion(facts: List<MemoryFact>, now: LocalTime): MemorySuggestion? {
    if (now.hour !in SUGGESTION_START_HOUR until SUGGESTION_END_HOUR) return null
    val liked = facts.filter { it.kind == MemoryKind.Preference }.maxByOrNull { it.savedAt } ?: return null
    val subject = factWords(liked.text).joinToString(" ").ifBlank { return null }
    return MemorySuggestion("${subject.replaceFirstChar(Char::uppercase)} for breakfast?", "order $subject")
}

private val PersonalKinds = setOf(MemoryKind.Preference, MemoryKind.Diet, MemoryKind.Allergy)

/** Up to [CHAT_FACT_LIMIT] facts for chat: all of them when few, else likes, diet and allergies first, newest first. */
internal fun chatFacts(facts: List<MemoryFact>): List<MemoryFact> =
    if (facts.size <= CHAT_FACT_LIMIT) facts
    else facts.sortedWith(compareByDescending<MemoryFact> { it.kind in PersonalKinds }.thenByDescending { it.savedAt }).take(CHAT_FACT_LIMIT)

/** The chat persona with the user's own facts appended; unchanged when there are none. */
internal fun personaWith(persona: String, facts: List<MemoryFact>): String {
    val chosen = chatFacts(facts).ifEmpty { return persona }
    return persona + "\nThings the user told you: " + chosen.joinToString("; ") { it.text } +
        ".\nUse them naturally when they help, and never invent other facts about the user."
}
