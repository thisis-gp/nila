package com.nila.launcher.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "If merchant contains SWIGGY, category is Food." Recipe rules come first and learned rules after, so a user's
 * correction wins over the app's defaults. Matching ignores case: bank SMS write the same merchant many ways.
 */
object RulesEngine {
    private const val CONTAINS = "contains"
    private const val EQUALS = "equals"

    fun apply(rules: List<JsonObject>, item: Item): Item =
        rules.fold(item) { current, rule -> if (matches(rule, current)) current.copy(fields = current.fields + sets(rule)) else current }

    /** Remembers "[field] = [value] means [set]", replacing what was learned before for the same value. */
    fun learn(rules: List<JsonObject>, field: String, value: String, set: Map<String, String>): List<JsonObject> {
        val kept = rules.filterNot { condition(it)?.let { c -> c.string("field") == field && c.string("value").equals(value, ignoreCase = true) } == true }
        val learned = JsonObject(
            mapOf(
                "when" to JsonObject(mapOf("op" to JsonPrimitive(EQUALS), "field" to JsonPrimitive(field), "value" to JsonPrimitive(value))),
                "set" to JsonObject(set.mapValues { JsonPrimitive(it.value) }),
            ),
        )
        return kept + learned
    }

    private fun condition(rule: JsonObject) = rule["when"] as? JsonObject

    private fun matches(rule: JsonObject, item: Item): Boolean {
        val condition = condition(rule) ?: return false
        val actual = item.string(condition.string("field").orEmpty()) ?: return false
        val expected = condition.string("value") ?: return false
        return when (condition.string("op")) {
            CONTAINS -> actual.contains(expected, ignoreCase = true)
            EQUALS -> actual.equals(expected, ignoreCase = true)
            else -> false
        }
    }

    private fun sets(rule: JsonObject) = (rule["set"] as? JsonObject).orEmpty().filterValues { it.stringOrNull() != null }
}
