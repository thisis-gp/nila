package com.nila.launcher.skills

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppRuntimeTest {
    @get:Rule val folder = TemporaryFolder()

    private val clock = Clock.fixed(Instant.parse("2026-10-15T04:30:00Z"), ZoneId.of("Asia/Kolkata"))
    private fun example(template: String) = Json.parseToJsonElement(RecipeExamples.forTemplate(template)).jsonObject
    private fun action(json: String) = Json.parseToJsonElement(json).jsonObject
    private fun grantsOf(recipe: JsonObject) = (recipe.getValue("permissions") as kotlinx.serialization.json.JsonArray).mapNotNull { it.stringOrNull() }.toSet()

    private fun runtime(
        recipe: JsonObject,
        grants: Set<String> = grantsOf(recipe),
        transactions: TransactionSource = TransactionSource { emptyList() },
    ) = AppRuntime(recipe, grants, FileAppStore(folder.root), transactions, clock)

    @Test
    fun counterStepsAreCountedForToday() {
        val water = example("counter")
        val app = runtime(water)
        repeat(2) { assertEquals(ActionResult.Done, app.dispatch(action("""{"action":"counter.step"}"""), target = null, values = emptyMap())) }
        assertEquals(FnValue.Number(2.0), app.value(action("""{"fn":"count","of":"/items","period":"day"}""")))
    }

    @Test
    fun formAddsShowUpInTheList() {
        val app = runtime(example("log"))
        app.dispatch(action("""{"action":"storage.add"}"""), target = null, values = mapOf("name" to JsonPrimitive("Dune"), "pages" to JsonPrimitive(412)))
        val books = (app.items("items") as DataResult.Items).items
        assertEquals(listOf("Dune"), books.map { it.string("name") })
        assertEquals(clock.millis().toDouble(), books.single().number(RecipeFunctions.DATE_FIELD))
    }

    @Test
    fun revokedPermissionDeniesReadsAndWrites() {
        val app = runtime(example("log"), grants = emptySet())
        assertEquals(DataResult.Denied("storage.local"), app.items("items"))
        assertEquals(ActionResult.Denied("storage.local"), app.dispatch(action("""{"action":"storage.add"}"""), null, emptyMap()))
        assertTrue(folder.root.list()!!.isEmpty())
    }

    @Test
    fun correctingOnePaymentCategorizesTheNextOnes() {
        fun pay(id: String, merchant: String) = Item(id, mapOf("merchant" to JsonPrimitive(merchant), "amount" to JsonPrimitive(25_000), "date" to JsonPrimitive(clock.millis())))
        val payments = listOf(pay("1", "UBER"), pay("2", "UBER"), pay("3", "SWIGGY*BLR"))
        val app = runtime(example("money"), transactions = TransactionSource { payments })

        val result = app.dispatch(action("""{"action":"rules.learn"}"""), target = payments[0], values = mapOf("category" to JsonPrimitive("Travel")))

        assertEquals(ActionResult.Done, result)
        val categories = (app.items("txns") as DataResult.Items).items.associate { it.id to it.string("category") }
        assertEquals(mapOf("1" to "Travel", "2" to "Travel", "3" to "Food"), categories)
        assertEquals(mapOf("Travel" to 50_000.0, "Food" to 25_000.0), app.chart("/txns", groupBy = "category", period = "month"))
    }

    @Test
    fun learningWithoutARulesSourceIsRejected() {
        val app = runtime(example("log"))
        val result = app.dispatch(action("""{"action":"rules.learn"}"""), target = Item("x", emptyMap()), values = emptyMap())
        assertTrue(result is ActionResult.Rejected)
    }
}
