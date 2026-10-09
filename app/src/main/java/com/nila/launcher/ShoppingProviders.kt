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
