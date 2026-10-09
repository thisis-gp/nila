package com.nila.launcher

enum class ShoppingCategory { FOOD, GROCERY }

private val FOOD_KEYWORDS = listOf(
    "biryani", "pizza", "burger", "dosa", "meal", "dinner", "lunch", "breakfast",
    "noodles", "curry", "thali", "sandwich", "roll", "momos", "shawarma", "cake",
)

/** Deterministic stand-in for FunctionGemma, NILA's intended real tool router (ARCHITECTURE.md). Swap when that's integrated. */
fun classifyShoppingIntent(item: String): ShoppingCategory {
    val lower = item.lowercase()
    return if (FOOD_KEYWORDS.any(lower::contains)) ShoppingCategory.FOOD else ShoppingCategory.GROCERY
}

private val ORDER_TRIGGERS = listOf("buy ", "order ", "get me ", "i need ", "purchase ")

/** Strips a leading ordering phrase and returns the item, or null if the prompt doesn't start with one. Still pattern matching, not real language understanding — see classifyShoppingIntent. */
fun extractOrderItem(prompt: String): String? {
    val lower = prompt.lowercase()
    val trigger = ORDER_TRIGGERS.firstOrNull { lower.startsWith(it) } ?: return null
    return prompt.substring(trigger.length).trim().takeIf { it.isNotBlank() }
}

private val SIZE_PATTERN = Regex(
    """(\d+(?:\.\d+)?)\s*(ml|millilitre|millilitres|l|litre|litres|liter|liters|kg|kilogram|kilograms|g|gram|grams|gm)\b""",
    RegexOption.IGNORE_CASE,
)

data class ParsedOrderItem(val searchQuery: String, val desiredSize: String?)

/** Pulls a size token ("1L", "500ml") out of the item text so it doesn't pollute the search query, keeping it to prefer matching results instead. */
fun parseOrderItem(item: String): ParsedOrderItem {
    val match = SIZE_PATTERN.find(item)
    val cleaned = SIZE_PATTERN.replace(item, "").replace(Regex("""\s+"""), " ").trim()
    return ParsedOrderItem(searchQuery = cleaned.ifBlank { item }, desiredSize = match?.value)
}

private fun toBaseUnits(value: Double, unit: String): Pair<String, Double>? = when (unit.lowercase()) {
    "ml", "millilitre", "millilitres" -> "ml" to value
    "l", "litre", "litres", "liter", "liters" -> "ml" to value * 1000
    "g", "gram", "grams", "gm" -> "g" to value
    "kg", "kilogram", "kilograms" -> "g" to value * 1000
    else -> null
}

/** Normalizes a size mention to (unit, value) in ml or g, so "1L" and "1000 ml" compare equal. Null when no size is present. */
fun normalizedSize(text: String): Pair<String, Double>? {
    val match = SIZE_PATTERN.find(text) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return toBaseUnits(value, match.groupValues[2])
}

/**
 * Swiggy MCP access is still pending written confirmation (RESEARCH.md / HACKATHON.md). This is
 * a clearly labeled placeholder group, never fake data, occupying the exact slot a real Swiggy
 * result group will take once access arrives — swap this call for a real search() call, nothing
 * else about the flow changes. Shown alongside other providers' real results, not as a fallback.
 */
fun swiggyPendingGroup(category: ShoppingCategory): ProviderGroup {
    val surface = if (category == ShoppingCategory.FOOD) "Swiggy Food" else "Swiggy Instamart"
    return ProviderGroup(
        providerName = surface,
        summary = "access pending",
        items = emptyList(),
        note = "Not connected yet — waiting on Swiggy's developer access confirmation.",
    )
}
