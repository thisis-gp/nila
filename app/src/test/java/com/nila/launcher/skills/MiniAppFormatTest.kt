package com.nila.launcher.skills

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniAppFormatTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(LocalDateTime.of(2026, 10, 15, 10, 0).atZone(zone).toInstant(), zone)

    @Test
    fun moneyUsesIndianGroupingAndWholeRupees() {
        assertEquals("₹12,480", MiniAppFormat.money(1_248_000.0))
        assertEquals("₹1,24,500", MiniAppFormat.money(12_450_000.0))
        assertEquals("₹486", MiniAppFormat.money(48_640.0))
        assertEquals("₹0.50", MiniAppFormat.money(50.0))
        assertEquals("₹0", MiniAppFormat.money(0.0))
    }

    @Test
    fun numbersKeepOneDecimalOnlyWhenNeeded() {
        assertEquals("72.5", MiniAppFormat.number(72.5))
        assertEquals("8", MiniAppFormat.number(8.0))
    }

    @Test
    fun insightComparesWithThePreviousPeriod() {
        assertEquals("₹1,920 less than September", MiniAppFormat.insight(1_056_000.0, 1_248_000.0, "month", clock, MiniAppFormat::money))
        assertEquals("2 more than yesterday", MiniAppFormat.insight(5.0, 3.0, "day", clock, MiniAppFormat::number))
        assertEquals("Same as last week", MiniAppFormat.insight(4.0, 4.0, "week", clock, MiniAppFormat::number))
        assertNull(MiniAppFormat.insight(5.0, null, "day", clock, MiniAppFormat::number))
        assertNull(MiniAppFormat.insight(5.0, 3.0, "all", clock, MiniAppFormat::number))
    }

    @Test
    fun theWeekBeforeRunsMondayToSunday() {
        fun on(day: Int) = Item("$day", mapOf("date" to JsonPrimitive(LocalDateTime.of(2026, 10, day, 9, 0).atZone(zone).toInstant().toEpochMilli())))
        val items = listOf(on(4), on(5), on(11), on(12))
        assertEquals(listOf("5", "11"), RecipeFunctions.inPeriod(items, "week", clock, back = 1).map { it.id })
    }
}
