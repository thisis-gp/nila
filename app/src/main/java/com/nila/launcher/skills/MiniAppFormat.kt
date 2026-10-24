package com.nila.launcher.skills

import java.time.Clock
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Text for mini-app screens. The insight line is computed here, never written by a model, so opening an app costs no Gemma call. */
object MiniAppFormat {
    private const val PAISE_PER_RUPEE = 100.0
    private const val RUPEE = "₹"
    private const val THOUSANDS = 3
    private const val LAKH_GROUP = 2

    /** Paise to rupees with Indian grouping (₹1,24,500); whole rupees, except amounts under ₹1. */
    fun money(paise: Double): String {
        val rupees = paise / PAISE_PER_RUPEE
        if (rupees > 0 && rupees < 1) return RUPEE + "%.2f".format(Locale.ROOT, rupees)
        return RUPEE + indianGrouping(rupees.roundToLong())
    }

    fun number(value: Double): String = if (value == floor(value)) value.toLong().toString() else "%.1f".format(Locale.ROOT, value)

    /** "₹1,920 less than September", "2 more than yesterday"; null when there is nothing to compare with. */
    fun insight(current: Double, previous: Double?, period: String?, clock: Clock, format: (Double) -> String): String? {
        if (previous == null) return null
        val before = when (period) {
            "day" -> "yesterday"
            "week" -> "last week"
            "month" -> LocalDate.now(clock).minusMonths(1).month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            "year" -> "last year"
            else -> return null
        }
        val change = current - previous
        return when {
            change == 0.0 -> "Same as $before"
            change > 0 -> "${format(change)} more than $before"
            else -> "${format(-change)} less than $before"
        }
    }

    private fun indianGrouping(value: Long): String {
        val digits = abs(value).toString()
        val sign = if (value < 0) "-" else ""
        if (digits.length <= THOUSANDS) return sign + digits
        val groups = ArrayDeque<String>()
        var rest = digits.dropLast(THOUSANDS)
        while (rest.length > LAKH_GROUP) {
            groups.addFirst(rest.takeLast(LAKH_GROUP))
            rest = rest.dropLast(LAKH_GROUP)
        }
        groups.addFirst(rest)
        return sign + groups.joinToString(",") + "," + digits.takeLast(THOUSANDS)
    }
}

private const val PAISE_PER_RUPEE_INPUT = 100.0

/**
 * What a form field saves. Numbers are saved as numbers so sums and charts can use them. Money is typed in rupees
 * and saved in paise, like bank transactions, so ₹500 is never shown as ₹5. Anything unparseable stays text.
 */
internal fun typedInput(text: String, type: String): JsonElement {
    val number = text.trim().toDoubleOrNull()
    return when {
        number == null -> JsonPrimitive(text)
        type == "money" -> JsonPrimitive(Math.round(number * PAISE_PER_RUPEE_INPUT))
        type == "number" -> JsonPrimitive(number)
        else -> JsonPrimitive(text)
    }
}
