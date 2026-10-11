package com.nila.launcher

private val NameFact = Regex("""^\s*my name is\s+(.+?)\s*[.!]?\s*$""", RegexOption.IGNORE_CASE)
private val Apostrophe = Regex("[’‘]")
private val MealFor = Regex("""\bfor (breakfast|lunch|dinner|a snack|snacks?)\b""", RegexOption.IGNORE_CASE)
private val Feeling = Regex("""^i\s+(like|love|enjoy|prefer)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val Being = Regex("""^i(?:'m| am)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val Avoiding = Regex("""^i\s+(don't|do not|can't|cannot)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val Owning = Regex("""^my\s+(.+?)\s+(is|are)\s+(.+)$""", RegexOption.IGNORE_CASE)

internal const val NAME_PREFIX = "My name is "

/** The name a "My name is X" fact about the user holds, or null when none was saved. */
internal fun userName(facts: List<MemoryFact>): String? =
    facts.firstOrNull { it.about == ME && NameFact.matches(it.text) }?.let { NameFact.find(it.text)?.groupValues?.get(1) }

/** The fact that holds the user's name, so it can be edited and left out of the fact rows. */
internal fun isNameFact(fact: MemoryFact): Boolean = fact.about == ME && NameFact.matches(fact.text)

/** A stored first-person note as a clean line: "I like filter coffee" gives "Likes filter coffee". The stored text is not changed. */
internal fun factLine(text: String): String {
    val t = Apostrophe.replace(text.trim().trimEnd('.', '!'), "'")
    val line = when {
        Feeling.matches(t) -> Feeling.find(t)!!.let { m ->
            val rest = m.groupValues[2]
            if (MealFor.containsMatchIn(rest)) rest else verbFor(m.groupValues[1]) + " " + rest
        }
        Avoiding.matches(t) -> Avoiding.find(t)!!.let { m -> negative(m.groupValues[1]) + " " + m.groupValues[2] }
        Being.matches(t) -> Being.find(t)!!.groupValues[1]
        Owning.matches(t) -> Owning.find(t)!!.let { m -> "${m.groupValues[1]} ${m.groupValues[2].lowercase()} ${m.groupValues[3]}" }
        else -> t
    }
    return line.replaceFirstChar(Char::uppercase)
}

private fun verbFor(verb: String) = when (verb.lowercase()) {
    "like" -> "likes"
    "love" -> "loves"
    "enjoy" -> "enjoys"
    else -> "prefers"
}

private fun negative(word: String) = if (word.lowercase().startsWith("c")) "can't" else "doesn't"

/** Only the kinds worth a tag: Diet and Allergy. Everything else reads fine without a label. */
internal fun factTag(kind: MemoryKind): String? = when (kind) {
    MemoryKind.Diet -> "Diet"
    MemoryKind.Allergy -> "Allergy"
    else -> null
}

/** "3 things NILA knows", or "1 thing". */
internal fun knownCount(count: Int): String = "$count ${if (count == 1) "thing" else "things"} NILA knows"
