package com.nila.launcher.skills

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeFunctionsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    /** Thursday 15 October 2026, 10:00 in India. */
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 15, 10, 0).atZone(zone).toInstant(), zone)

    private fun at(day: Int, month: Int = 10, hour: Int = 9) = LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun item(id: String, date: Long?, vararg fields: Pair<String, Any>) = Item(
        id,
        buildMap {
            date?.let { put("date", JsonPrimitive(it)) }
            for ((k, v) in fields) put(k, if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()))
        },
    )

    private fun fn(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun countsTodayOnly() {
        val glasses = listOf(item("a", at(15, hour = 8)), item("b", at(15, hour = 9)), item("c", at(14)))
        assertEquals(FnValue.Number(2.0), RecipeFunctions.evaluate(fn("""{"fn":"count","of":"/items","period":"day"}"""), glasses, clock))
    }

    @Test
    fun weekStartsMonday() {
        val sessions = listOf(item("sun", at(11)), item("mon", at(12)), item("thu", at(15)))
        assertEquals(listOf("mon", "thu"), RecipeFunctions.inPeriod(sessions, "week", clock).map { it.id })
    }

    @Test
    fun sumsMoneyThisMonth() {
        val payments = listOf(item("a", at(2), "amount" to 25_000), item("b", at(15), "amount" to 12_050), item("c", at(30, month = 9), "amount" to 99_900))
        assertEquals(FnValue.Number(37_050.0), RecipeFunctions.evaluate(fn("""{"fn":"sum","of":"/txns","field":"amount","period":"month"}"""), payments, clock))
    }

    @Test
    fun skipsItemsWithNoDateOrNonNumberAmount() {
        val items = listOf(item("dated", at(15), "amount" to 100), item("undated", null, "amount" to 500), item("text", at(15), "amount" to "100"))
        assertEquals(FnValue.Number(100.0), RecipeFunctions.evaluate(fn("""{"fn":"sum","of":"/txns","field":"amount","period":"day"}"""), items, clock))
        assertEquals(FnValue.Number(600.0), RecipeFunctions.evaluate(fn("""{"fn":"sum","of":"/txns","field":"amount"}"""), items, clock))
    }

    @Test
    fun groupsCountsAndSums() {
        val payments = listOf(item("a", at(1), "category" to "Food", "amount" to 300), item("b", at(2), "category" to "Food", "amount" to 200), item("c", at(3), "amount" to 50))
        assertEquals(mapOf("Food" to 500.0, "Other" to 50.0), RecipeFunctions.group(payments, "category", "amount"))
        assertEquals(mapOf("Food" to 2.0, "Other" to 1.0), RecipeFunctions.group(payments, "category", null))
        assertEquals(FnValue.Groups(mapOf("Food" to 2.0, "Other" to 1.0)), RecipeFunctions.evaluate(fn("""{"fn":"groupBy","of":"/txns","field":"category"}"""), payments, clock))
    }

    @Test
    fun otherFunctionsAreUnsupported() {
        assertEquals(FnValue.Unsupported("formatMoney"), RecipeFunctions.evaluate(fn("""{"fn":"formatMoney","of":"/txns"}"""), emptyList(), clock))
    }
}
