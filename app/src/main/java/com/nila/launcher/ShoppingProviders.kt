package com.nila.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
 * Shown instead of attempting a Zepto call when there's no stored token yet. Unlike Swiggy, Zepto
 * genuinely can be connected right now (self-serve OAuth, see ZeptoAuth) — so this offers a real
 * one-tap connect action rather than just an explanatory note.
 */
fun zeptoNotConnectedGroup(
    zeptoAuth: ZeptoAuth,
    context: Context,
    coroutineScope: CoroutineScope,
    appendTurn: (Turn) -> Unit,
): ProviderGroup = ProviderGroup(
    providerName = "Zepto",
    summary = "not connected",
    items = listOf(
        AsyncChoice(
            title = "Connect Zepto",
            detail = "Sign in to search and order groceries",
            price = "",
            eta = "",
        ) {
            appendTurn(Turn("Zepto", NilaResponse.Text("Opening Zepto login in your browser. Sign in, then ask again.")))
            coroutineScope.launch(Dispatchers.IO) {
                zeptoAuth.connect(context)
                    .onSuccess { Log.i("ZeptoAuth", "Zepto authorization completed.") }
                    .onFailure { Log.w("ZeptoAuth", "Zepto authorization failed: ${it.message}") }
            }
        },
    ),
)
