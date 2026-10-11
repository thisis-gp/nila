package com.nila.launcher.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** What kind of value a component prop holds. ID and ID_LIST point at other components by id. */
enum class PropKind { TEXT, NUMBER, ENUM, PATH, ID, ID_LIST, FUNCTION, ACTION }

data class PropSpec(val kind: PropKind, val required: Boolean = false, val values: Set<String> = emptySet())

data class ComponentSpec(val name: String, val props: Map<String, PropSpec>)

/** Native data a recipe may read. [ownStore] providers keep per-app data; transactions are the phone's own. */
data class ProviderSpec(val name: String, val permission: String, val ownStore: Boolean, val fields: List<String>)

/** Everything a recipe may name. The validator, the repair step and the model prompt are all built from this one list. */
object RecipeCatalog {
    const val FORMAT = "nila.app/1"
    const val ROOT_ID = "root"
    const val ROOT_COMPONENT = "Column"
    const val MAX_COMPONENTS = 40
    const val MAX_ID_CHARS = 40
    const val NO_TEMPLATE = "none"

    /** Keys every component has besides its props. */
    val COMPONENT_KEYS = setOf("id", "component")
    val RULE_OPS = setOf("contains", "equals")
    val functions = setOf("sum", "count", "groupBy", "filter", "formatMoney", "formatDate", "contains", "equals")
    val actions = setOf("rules.learn", "storage.add", "storage.update", "storage.delete", "counter.step")
    val periods = setOf("day", "week", "month", "year", "all")
    val templates = listOf("money", "log", "counter", "checklist")

    val providers: Map<String, ProviderSpec> = listOf(
        ProviderSpec("transactions", "transactions.read", ownStore = false, fields = listOf("amount", "merchant", "category", "date")),
        ProviderSpec("storage", "storage.local", ownStore = true, fields = emptyList()),
        ProviderSpec("rules", "storage.local", ownStore = true, fields = emptyList()),
    ).associateBy { it.name }

    val permissions: Set<String> = providers.values.map { it.permission }.toSet()

    private fun required(kind: PropKind, values: Set<String> = emptySet()) = PropSpec(kind, required = true, values = values)
    private fun optional(kind: PropKind, values: Set<String> = emptySet()) = PropSpec(kind, values = values)

    val components: Map<String, ComponentSpec> = listOf(
        ComponentSpec("Column", mapOf("children" to required(PropKind.ID_LIST))),
        ComponentSpec("Row", mapOf("children" to required(PropKind.ID_LIST))),
        ComponentSpec("Text", mapOf("text" to required(PropKind.TEXT), "style" to optional(PropKind.ENUM, setOf("title", "body", "caption")))),
        ComponentSpec("StatTile", mapOf("label" to required(PropKind.TEXT), "value" to required(PropKind.FUNCTION))),
        ComponentSpec(
            "Chart",
            mapOf(
                "kind" to required(PropKind.ENUM, setOf("donut", "bar", "line")),
                "data" to required(PropKind.PATH),
                "groupBy" to optional(PropKind.TEXT),
                "period" to optional(PropKind.ENUM, periods),
            ),
        ),
        ComponentSpec("List", mapOf("items" to required(PropKind.PATH), "template" to required(PropKind.ID), "empty" to optional(PropKind.TEXT))),
        ComponentSpec("TxnRow", mapOf("onCategoryChange" to optional(PropKind.ACTION))),
        ComponentSpec("ItemRow", mapOf("titleField" to required(PropKind.TEXT), "subtitleField" to optional(PropKind.TEXT), "onTap" to optional(PropKind.ACTION))),
        ComponentSpec("Form", mapOf("fields" to required(PropKind.ID_LIST), "submit" to required(PropKind.ACTION), "submitLabel" to optional(PropKind.TEXT))),
        ComponentSpec(
            "Input",
            mapOf("label" to required(PropKind.TEXT), "field" to required(PropKind.TEXT), "type" to required(PropKind.ENUM, setOf("text", "number", "money", "date"))),
        ),
        ComponentSpec("Checklist", mapOf("items" to required(PropKind.PATH), "labelField" to required(PropKind.TEXT), "onToggle" to optional(PropKind.ACTION))),
        ComponentSpec("Counter", mapOf("label" to required(PropKind.TEXT), "value" to required(PropKind.FUNCTION), "onStep" to required(PropKind.ACTION))),
        ComponentSpec("Button", mapOf("label" to required(PropKind.TEXT), "onClick" to required(PropKind.ACTION))),
    ).associateBy { it.name }
}

internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun Map<String, JsonElement>.string(key: String): String? = this[key].stringOrNull()

/** Ids this component points at through its ID and ID_LIST props, in prop order. */
internal fun ComponentSpec.refs(component: Map<String, JsonElement>): List<String> = props.flatMap { (name, prop) ->
    when (prop.kind) {
        PropKind.ID -> listOfNotNull(component[name].stringOrNull())
        PropKind.ID_LIST -> (component[name] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }
        else -> emptyList()
    }
}
