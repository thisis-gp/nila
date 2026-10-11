package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesEngineTest {
    private fun rule(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun payment(merchant: String) = Item("p", mapOf("merchant" to JsonPrimitive(merchant), "amount" to JsonPrimitive(25_000)))

    private val swiggyIsFood = rule("""{"when":{"op":"contains","field":"merchant","value":"swiggy"},"set":{"category":"Food"}}""")

    @Test
    fun containsMatchesAnyCase() {
        assertEquals("Food", RulesEngine.apply(listOf(swiggyIsFood), payment("SWIGGY*BLR")).string("category"))
    }

    @Test
    fun laterRulesWin() {
        val instamartIsGroceries = rule("""{"when":{"op":"equals","field":"merchant","value":"swiggy instamart"},"set":{"category":"Groceries"}}""")
        assertEquals("Groceries", RulesEngine.apply(listOf(swiggyIsFood, instamartIsGroceries), payment("Swiggy Instamart")).string("category"))
    }

    @Test
    fun learningTheSameMerchantReplacesTheOldRule() {
        val once = RulesEngine.learn(emptyList(), "merchant", "UBER", mapOf("category" to "Travel"))
        val twice = RulesEngine.learn(once, "merchant", "uber", mapOf("category" to "Work"))
        assertEquals(1, twice.size)
        assertEquals("Work", RulesEngine.apply(twice, payment("UBER")).string("category"))
    }

    @Test
    fun itemsWithoutTheFieldAreUntouched() {
        val water = Item("w", mapOf("date" to JsonPrimitive(1L)))
        assertEquals(water, RulesEngine.apply(listOf(swiggyIsFood), water))
    }
}
