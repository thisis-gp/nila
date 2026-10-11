package com.nila.launcher.skills

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.serialization.json.JsonObject

sealed interface FnValue {
    data class Number(val value: Double) : FnValue
    data class Groups(val groups: Map<String, Double>) : FnValue
    /** A catalog function the runtime cannot compute into a value, such as a formatter; the renderer shows nothing. */
    data class Unsupported(val name: String) : FnValue
}

/** The named functions a recipe may call (spec §4). Native code only: recipes never carry expressions to run. */
object RecipeFunctions {
    const val DATE_FIELD = "date"
    const val OTHER_GROUP = "Other"
    private const val SUM = "sum"
    private const val COUNT = "count"
    private const val GROUP_BY = "groupBy"

    fun evaluate(fn: JsonObject, items: List<Item>, clock: Clock): FnValue {
        val name = fn.string("fn").orEmpty()
        val inPeriod = inPeriod(items, fn.string("period"), clock)
        val field = fn.string("field")
        return when (name) {
            COUNT -> FnValue.Number(inPeriod.size.toDouble())
            SUM -> FnValue.Number(inPeriod.mapNotNull { item -> field?.let(item::number) }.sum())
            GROUP_BY -> FnValue.Groups(group(inPeriod, field.orEmpty(), valueField = null))
            else -> FnValue.Unsupported(name)
        }
    }

    /** Items dated within the current [period]; no period (or "all") keeps every item, dated or not. */
    fun inPeriod(items: List<Item>, period: String?, clock: Clock): List<Item> {
        val today = LocalDate.now(clock)
        val start = when (period) {
            "day" -> today
            "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "month" -> today.withDayOfMonth(1)
            "year" -> today.withDayOfYear(1)
            else -> return items
        }
        return items.filter { item ->
            val millis = item.number(DATE_FIELD)?.toLong() ?: return@filter false
            val day = Instant.ofEpochMilli(millis).atZone(clock.zone).toLocalDate()
            !day.isBefore(start) && !day.isAfter(today)
        }
    }

    /** Totals of [valueField] per [field] value, or counts when there is no value field. Unlabelled items go to "Other". */
    fun group(items: List<Item>, field: String, valueField: String?): Map<String, Double> =
        items.groupBy { it.string(field) ?: OTHER_GROUP }
            .mapValues { (_, group) -> if (valueField == null) group.size.toDouble() else group.sumOf { it.number(valueField) ?: 0.0 } }
}
