package com.nila.launcher

import java.time.LocalDateTime

/** One gentle nudge on Home: the line NILA says, the order it sends on "Yes", and where it came from. */
internal data class MemorySuggestion(
    val label: String,
    val request: String,
    val reason: String? = null,
    val meal: Meal? = null,
    val dish: String? = null,
)

/** A meal window by hour of the day; outside every window NILA suggests no food. */
internal enum class Meal(val word: String, val hours: IntRange) {
    Breakfast("breakfast", 6..11),
    Lunch("lunch", 12..15),
    Snack("snack", 16..18),
    Dinner("dinner", 19..22),
}

private class DefaultMeal(val line: String, val dish: String)

private val DefaultMeals = mapOf(
    Meal.Breakfast to DefaultMeal("Idli for breakfast?", "idli"),
    Meal.Lunch to DefaultMeal("Something light for lunch?", "veg thali"),
    Meal.Snack to DefaultMeal("Something small for a snack?", "samosa"),
    Meal.Dinner to DefaultMeal("Something light for dinner?", "dal khichdi"),
)

private val MeatWords = setOf("chicken", "mutton", "fish", "prawn", "prawns", "egg", "eggs", "beef", "pork", "lamb", "keema", "kebab", "seafood")
private val JainWord = Regex("""\bjain\b""")

/** The meal whose window holds [hour], or null. */
internal fun mealAt(hour: Int): Meal? = Meal.entries.firstOrNull { hour in it.hours }

/**
 * One food suggestion for the meal now: a dish the user likes for this meal, else a plain default. Veg and
 * Jain diets rule out meat, allergies rule out their allergen, and outside a meal window there is none.
 */
internal fun homeSuggestion(facts: List<MemoryFact>, now: LocalDateTime): MemorySuggestion? {
    val meal = mealAt(now.hour) ?: return null
    val mine = facts.filter { it.about == ME }
    val dietTexts = mine.filter { it.kind == MemoryKind.Diet && appliesAt(it.text, now) }.map { it.text.lowercase() }
    val veg = dietTexts.any { VegWord.containsMatchIn(it) || JainWord.containsMatchIn(it) }
    val allergens = mine.filter { it.kind == MemoryKind.Allergy }.flatMap { factWords(it.text) }

    fun allowed(dish: String) =
        allergens.none { mentions(dish, it) } && !(veg && words(dish).any { it in MeatWords })

    val liked = mine.filter { it.kind == MemoryKind.Preference && mentions(it.text, meal.word) }
        .sortedByDescending { it.savedAt }
        .firstNotNullOfOrNull { fact -> factWords(fact.text).joinToString(" ").takeIf { it.isNotBlank() && allowed(it) }?.let { fact to it } }
    if (liked != null) {
        val (fact, dish) = liked
        return MemorySuggestion("${dish.replaceFirstChar(Char::uppercase)} for ${meal.word}?", "order $dish", becauseYou(fact.text), meal, dish)
    }
    val default = DefaultMeals.getValue(meal).takeIf { allowed(it.dish) } ?: return null
    val reason = when {
        !veg -> null
        dietTexts.any { JainWord.containsMatchIn(it) } -> "You're Jain, so veg places only"
        else -> "You're vegetarian, so veg places only"
    }
    return MemorySuggestion(default.line, "order ${default.dish}", reason, meal, default.dish)
}

private val Apostrophes = Regex("[’‘]")
private val YouAre = Regex("""^i(?:'m| am)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val YouFeel = Regex("""^i\s+(like|love|enjoy|prefer|hate)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val YourThing = Regex("""^my\s+(.+?)\s+(is|are)\s+(.+)$""", RegexOption.IGNORE_CASE)

/** A first-person fact as a short reason: "I like masala dosa" gives "Because you like masala dosa". */
internal fun becauseYou(fact: String): String {
    val text = Apostrophes.replace(fact.trim().trimEnd('.', '!'), "'")
    YouFeel.find(text)?.let { return "Because you ${it.groupValues[1].lowercase()} ${it.groupValues[2]}" }
    YouAre.find(text)?.let { return "Because you're ${it.groupValues[1]}" }
    YourThing.find(text)?.let { return "Because your ${it.groupValues[1]} ${it.groupValues[2].lowercase()} ${it.groupValues[3]}" }
    return "From what you told me"
}

/** The food photo that fits a dish, by drawable name, or null. */
internal fun dishPhotoKey(dish: String?): String? = dish?.lowercase()?.let { d ->
    when {
        "biryani" in d -> "biryani"
        "salad" in d -> "salad"
        else -> null
    }
}
