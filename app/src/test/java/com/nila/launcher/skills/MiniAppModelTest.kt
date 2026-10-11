package com.nila.launcher.skills

import java.time.Clock
import java.time.LocalDateTime
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

class MiniAppModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 15, 10, 0).atZone(zone).toInstant(), zone)
    private fun at(day: Int, hour: Int = 9) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
    private fun example(template: String) = Json.parseToJsonElement(RecipeExamples.forTemplate(template)).jsonObject
    private fun grants(recipe: JsonObject) = (recipe.getValue("permissions") as kotlinx.serialization.json.JsonArray).mapNotNull { it.stringOrNull() }.toSet()
    private fun pay(id: String, merchant: String, paise: Int, day: Int) =
        Item(id, mapOf("merchant" to JsonPrimitive(merchant), "amount" to JsonPrimitive(paise), "date" to JsonPrimitive(at(day))))

    private fun screen(recipe: JsonObject, payments: List<Item> = emptyList(), granted: Set<String> = grants(recipe)): ScreenState {
        val runtime = AppRuntime(recipe, granted, FileAppStore(folder.root), TransactionSource { payments }, clock)
        return MiniAppModel.build(recipe, runtime, clock)
    }

    @Test
    fun moneyAppShowsTotalChartAndDaySections() {
        val payments = listOf(pay("1", "SWIGGY*BLR", 48_640, 15), pay("2", "UBER", 21_200, 15), pay("3", "ZEPTO", 113_800, 14))
        val blocks = screen(example("money"), payments).blocks

        val hero = blocks.first() as Block.Hero
        assertEquals("Spent this month" to "₹1,836", hero.label to hero.value)
        assertTrue(blocks.any { it is Block.Chart })
        val list = blocks.filterIsInstance<Block.ItemList>().single()
        assertEquals(listOf("Today", "Yesterday"), list.sections.map { it.title })
        val uber = list.sections.first().rows.single { it.title == "UBER" }
        assertEquals("₹212", uber.trailing)
        assertEquals(listOf("Food", MiniAppModel.OTHER_CHIP), uber.chips)
        val swiggy = list.sections.first().rows.single { it.title == "SWIGGY*BLR" }
        assertEquals(emptyList<String>(), swiggy.chips)
    }

    @Test
    fun counterAppShowsItsCountAndStep() {
        val water = example("counter")
        val runtime = AppRuntime(water, grants(water), FileAppStore(folder.root), TransactionSource { emptyList() }, clock)
        repeat(2) { runtime.dispatch(Json.parseToJsonElement("""{"action":"counter.step"}""").jsonObject, null, emptyMap()) }
        val counter = MiniAppModel.build(water, runtime, clock).blocks.first() as Block.Counter
        assertEquals("Glasses today" to "2", counter.label to counter.value)
    }

    @Test
    fun logAppOffersAnAddFormWithItsFields() {
        val form = screen(example("log")).blocks.filterIsInstance<Block.AddForm>().single()
        assertEquals(listOf("name" to "Book", "pages" to "Pages"), form.fields.map { it.field to it.label })
        assertEquals("Add book", form.submitLabel)
    }

    @Test
    fun missingGrantShowsABlockedStateNotACrash() {
        val blocks = screen(example("money"), granted = emptySet()).blocks
        assertEquals(listOf(Block.Blocked("transactions.read")), blocks.filterIsInstance<Block.Blocked>())
    }

    @Test
    fun aListOnlyAppStartsWithItsList() {
        val recipe = recipe("""[{"id":"root","component":"List","items":"/items","template":"row"},{"id":"row","component":"ItemRow","titleField":"name"}]""")
        assertTrue(screen(recipe).blocks.first() is Block.ItemList)
    }
}
