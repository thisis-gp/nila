package com.nila.launcher.skills

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schema for the screen Gemma fills, built from RecipeCatalog, so constrained decoding (spec §7.3) can only
 * produce catalog components with their required props. Gemma alone broke JSON in 4 of 25 apps on the PC eval.
 */
object RecipeSchema {
    val FILL: String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("components") {
                put("type", "array")
                putJsonObject("items") {
                    putJsonArray("anyOf") { RecipeCatalog.components.values.forEach { add(component(it)) } }
                }
            }
            put("rules", rules())
        }
        putJsonArray("required") { add("components") }
        put("additionalProperties", false)
    }.toString()

    private fun component(spec: ComponentSpec): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("id") { put("type", "string") }
            putJsonObject("component") { put("const", spec.name) }
            spec.props.forEach { (name, prop) -> put(name, prop(prop)) }
        }
        putJsonArray("required") {
            RecipeCatalog.COMPONENT_KEYS.forEach { add(it) }
            spec.props.filterValues { it.required }.keys.forEach { add(it) }
        }
        put("additionalProperties", false)
    }

    private fun prop(prop: PropSpec): JsonObject = when (prop.kind) {
        PropKind.TEXT, PropKind.PATH, PropKind.ID -> buildJsonObject { put("type", "string") }
        PropKind.NUMBER -> buildJsonObject { put("type", "number") }
        PropKind.ENUM -> enumOf(prop.values)
        PropKind.ID_LIST -> buildJsonObject {
            put("type", "array")
            putJsonObject("items") { put("type", "string") }
        }
        PropKind.FUNCTION -> closedObject(
            properties = mapOf(
                "fn" to enumOf(RecipeCatalog.functions),
                "of" to buildJsonObject { put("type", "string") },
                "field" to buildJsonObject { put("type", "string") },
                "period" to enumOf(RecipeCatalog.periods),
            ),
            required = listOf("fn", "of"),
        )
        PropKind.ACTION -> closedObject(
            properties = mapOf("action" to enumOf(RecipeCatalog.actions), "key" to buildJsonObject { put("type", "string") }),
            required = listOf("action"),
        )
    }

    private fun rules(): JsonObject = buildJsonObject {
        put("type", "array")
        put(
            "items",
            closedObject(
                properties = mapOf(
                    "when" to closedObject(
                        properties = mapOf(
                            "op" to enumOf(RecipeCatalog.RULE_OPS),
                            "field" to buildJsonObject { put("type", "string") },
                            "value" to buildJsonObject { put("type", "string") },
                        ),
                        required = listOf("op", "field", "value"),
                    ),
                    "set" to buildJsonObject {
                        put("type", "object")
                        putJsonObject("additionalProperties") { put("type", "string") }
                    },
                ),
                required = listOf("when", "set"),
            ),
        )
    }

    private fun enumOf(values: Collection<String>): JsonObject = buildJsonObject { putJsonArray("enum") { values.forEach { add(it) } } }

    private fun closedObject(properties: Map<String, JsonObject>, required: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }
}
