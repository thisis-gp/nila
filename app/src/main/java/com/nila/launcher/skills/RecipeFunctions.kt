package com.nila.launcher.skills

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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

    /** [back] evaluates over the period that many periods ago, for "less than September" comparisons. */
    fun evaluate(fn: JsonObject, items: List<Item>, clock: Clock, back: Int = 0): FnValue {
        val name = fn.string("fn").orEmpty()
        val inPeriod = inPeriod(items, fn.string("period"), clock, back)
        val field = fn.string("field")
        return when (name) {
            COUNT -> FnValue.Number(inPeriod.size.toDouble())
            SUM -> FnValue.Number(inPeriod.mapNotNull { item -> field?.let(item::number) }.sum())
            GROUP_BY -> FnValue.Groups(group(inPeriod, field.orEmpty(), valueField = null))
            else -> FnValue.Unsupported(name)
        }
    }

    /**
     * Items dated within the current [period]; no period (or "all") keeps every item. With [back], the period that many
     * periods earlier, cut to the same number of days elapsed so far, so 15 days of October compare with 15 of September.
     */
    fun inPeriod(items: List<Item>, period: String?, clock: Clock, back: Int = 0): List<Item> {
        val today = LocalDate.now(clock)
        val shift = back.toLong()
        val currentStart = when (period) {
            "day" -> today
            "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            "month" -> today.withDayOfMonth(1)
            "year" -> today.withDayOfYear(1)
            else -> return items
        }
        val (start, periodEnd) = when (period) {
            "day" -> currentStart.minusDays(shift).let { it to it }
            "week" -> currentStart.minusWeeks(shift).let { it to it.plusWeeks(1).minusDays(1) }
            "month" -> currentStart.minusMonths(shift).let { it to it.plusMonths(1).minusDays(1) }
            else -> currentStart.minusYears(shift).let { it to it.plusYears(1).minusDays(1) }
        }
        val end = minOf(periodEnd, start.plusDays(ChronoUnit.DAYS.between(currentStart, today)))
        return items.filter { item ->
            val millis = item.number(DATE_FIELD)?.toLong() ?: return@filter false
            val day = Instant.ofEpochMilli(millis).atZone(clock.zone).toLocalDate()
            !day.isBefore(start) && !day.isAfter(end)
        }
    }

    /** Totals of [valueField] per [field] value, or counts when there is no value field. Unlabelled items go to "Other". */
    fun group(items: List<Item>, field: String, valueField: String?): Map<String, Double> =
        items.groupBy { it.string(field) ?: OTHER_GROUP }
            .mapValues { (_, group) -> if (valueField == null) group.size.toDouble() else group.sumOf { it.number(valueField) ?: 0.0 } }
}
