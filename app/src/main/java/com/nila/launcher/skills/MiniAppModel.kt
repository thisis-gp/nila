package com.nila.launcher.skills

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a mini-app screen shows, already formatted. The Compose layer only lays these out (design: B + C's insight line). */
data class ScreenState(val title: String, val blocks: List<Block>)

sealed interface Block {
    data class Hero(val label: String, val value: String, val insight: String?) : Block
    data class Counter(val label: String, val value: String, val step: JsonObject) : Block
    data class Chart(val bars: List<Bar>) : Block
    data class ItemList(val sections: List<Section>, val more: Int, val onCategory: JsonObject?) : Block
    data class AddForm(val fields: List<Field>, val submitLabel: String, val submit: JsonObject) : Block
    data class Checklist(val rows: List<Check>, val toggle: JsonObject?) : Block
    data class Button(val label: String, val action: JsonObject) : Block
    data class Note(val text: String) : Block
    /** The data needs a permission the user has not granted; the screen offers "Grant access" instead of crashing. */
    data class Blocked(val permission: String) : Block
}

data class Bar(val label: String, val value: Double, val highlight: Boolean)
data class Section(val title: String, val rows: List<Row>)
/** [chips] are quick categories offered for a payment that has none yet (design C's quick replies). */
data class Row(val item: Item, val title: String, val detail: String, val trailing: String?, val chips: List<String>)
data class Field(val field: String, val label: String, val type: String)
data class Check(val item: Item, val label: String, val done: Boolean)

object MiniAppModel {
    const val OTHER_CHIP = "Other…"
    const val MAX_ROWS = 50
    private const val MAX_CHIPS = 3
    private const val AMOUNT = "amount"
    private const val CATEGORY = "category"
    private const val MERCHANT = "merchant"
    private const val DONE = "done"
    private const val DEFAULT_SUBMIT = "Add"
    private const val MONEY_INPUT = "money"
    private const val TXN_ROW = "TxnRow"
    private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun build(recipe: JsonObject, runtime: AppRuntime, clock: Clock): ScreenState {
        val byId = (recipe["components"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.string("id").orEmpty() }
        val blocks = mutableListOf<Block>()
        val pendingInputs = mutableListOf<Field>()
        val inputs = byId.values.filter { it.string("component") == "Input" }
        // Money is whatever a money Input writes; with no Input writing it, a bank payment's "amount" (in paise).
        val moneyFields = inputs.filter { it.string("type") == MONEY_INPUT }.mapNotNull { it.string("field") }.toSet() +
            setOf(AMOUNT).filter { amount -> inputs.none { it.string("field") == amount } }

        fun add(data: DataResult, make: (List<Item>) -> Block) {
            when (data) {
                is DataResult.Items -> blocks += make(data.items)
                is DataResult.Denied -> if (Block.Blocked(data.permission) !in blocks) blocks += Block.Blocked(data.permission)
            }
        }

        fun visit(component: JsonObject) {
            when (component.string("component")) {
                "Column", "Row" -> ids(component, "children").mapNotNull(byId::get).forEach(::visit)
                "Text" -> component.string("text")?.let { blocks += Block.Note(it) }
                "StatTile" -> {
                    val isFirst = blocks.none { it is Block.Hero || it is Block.Counter }
                    statTile(component, runtime, clock, isFirst, moneyFields)?.let { blocks += it }
                }
                "Counter" -> blocks += Block.Counter(component.string("label").orEmpty(), shown(runtime.value(component.obj("value"))), component.obj("onStep"))
                "Chart" -> add(runtime.items(key(component.string("data")))) {
                    val points = runtime.chart(component.string("data").orEmpty(), component.string("groupBy"), component.string("period"))
                    val top = points.maxByOrNull { it.value }?.key
                    Block.Chart(points.map { (label, value) -> Bar(label, value, highlight = label == top) })
                }
                "List" -> add(runtime.items(key(component.string("items")))) { items -> itemList(items, byId[component.string("template")], clock) }
                "Checklist" -> add(runtime.items(key(component.string("items")))) { items ->
                    val label = component.string("labelField").orEmpty()
                    val rows = items.map { Check(it, it.string(label).orEmpty(), (it.fields[DONE] as? JsonPrimitive)?.content == "true") }
                    Block.Checklist(rows, component["onToggle"] as? JsonObject)
                }
                "Form" -> blocks += Block.AddForm(
                    ids(component, "fields").mapNotNull(byId::get).map(::field),
                    component.string("submitLabel") ?: DEFAULT_SUBMIT,
                    component.obj("submit"),
                )
                "Input" -> pendingInputs += field(component)
                "Button" -> {
                    val action = component.obj("onClick")
                    val label = component.string("label").orEmpty()
                    // A loose Input followed by an add button is a one-field form (Gemma's journal pattern).
                    blocks += if (pendingInputs.isNotEmpty() && action.string("action") in RecipeCatalog.ADD_ACTIONS) {
                        Block.AddForm(pendingInputs.toList(), label, action).also { pendingInputs.clear() }
                    } else {
                        Block.Button(label, action)
                    }
                }
            }
        }

        byId[RecipeCatalog.ROOT_ID]?.let(::visit)
        return ScreenState(recipe.string("title").orEmpty(), blocks)
    }

    private fun statTile(component: JsonObject, runtime: AppRuntime, clock: Clock, isFirst: Boolean, moneyFields: Set<String>): Block? {
        val fn = component.obj("value")
        val current = (runtime.value(fn) as? FnValue.Number)?.value ?: return null
        val format: (Double) -> String = if (fn.string("field") in moneyFields) MiniAppFormat::money else MiniAppFormat::number
        val label = component.string("label").orEmpty()
        if (!isFirst) return Block.Note("$label: ${format(current)}")
        // Nothing last period reads as "nothing to compare", not "₹12,480 more than September".
        val previous = (runtime.value(fn, back = 1) as? FnValue.Number)?.value?.takeIf { it != 0.0 }
        return Block.Hero(label, format(current), MiniAppFormat.insight(current, previous, fn.string("period"), clock, format))
    }

    private fun itemList(items: List<Item>, template: JsonObject?, clock: Clock): Block.ItemList {
        val shown = items.sortedByDescending { it.number(RecipeFunctions.DATE_FIELD) ?: 0.0 }.take(MAX_ROWS)
        val payments = template?.string("component") == TXN_ROW
        val inUse = items.mapNotNull { it.string(CATEGORY) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(MAX_CHIPS)
        val rows = shown.map { item ->
            if (payments) {
                val category = item.string(CATEGORY)
                Row(
                    item,
                    item.string(MERCHANT).orEmpty(),
                    category.orEmpty(),
                    item.number(AMOUNT)?.let(MiniAppFormat::money),
                    chips = if (category == null) inUse + OTHER_CHIP else emptyList(),
                )
            } else {
                val title = template?.string("titleField")?.let(item::string).orEmpty()
                val detail = template?.string("subtitleField")?.let { (item.fields[it] as? JsonPrimitive)?.content }.orEmpty()
                Row(item, title, detail, trailing = null, chips = emptyList())
            }
        }
        val sections = rows.groupBy { dayTitle(it.item, clock) }.map { (title, sectionRows) -> Section(title, sectionRows) }
        return Block.ItemList(sections, more = items.size - shown.size, onCategory = template?.get("onCategoryChange") as? JsonObject)
    }

    private fun dayTitle(item: Item, clock: Clock): String {
        val millis = item.number(RecipeFunctions.DATE_FIELD) ?: return "Earlier"
        val day = Instant.ofEpochMilli(millis.toLong()).atZone(clock.zone).toLocalDate()
        val today = LocalDate.now(clock)
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(SHORT_DATE)
        }
    }

    private fun field(input: JsonObject) = Field(input.string("field").orEmpty(), input.string("label").orEmpty(), input.string("type").orEmpty())
    private fun shown(value: FnValue) = (value as? FnValue.Number)?.value?.let(MiniAppFormat::number).orEmpty()
    private fun ids(component: JsonObject, prop: String) = (component[prop] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }
    private fun key(path: String?) = path.orEmpty().removePrefix("/").substringBefore('/')
    private fun JsonObject.obj(prop: String) = this[prop] as? JsonObject ?: JsonObject(emptyMap())
}
