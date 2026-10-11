package com.nila.launcher.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One problem in a recipe. [where] is a component id or top-level key, so the model can be told exactly what to fix. */
data class RecipeError(val where: String, val message: String) {
    override fun toString() = "$where: $message"
}

/** Boundary check for recipes: model output and marketplace files are untrusted until this returns no errors. */
object RecipeValidator {
    private val APP_ID = Regex("[a-z0-9][a-z0-9-]{0,${RecipeCatalog.MAX_ID_CHARS - 1}}")

    fun validate(recipe: JsonObject): List<RecipeError> {
        val errors = mutableListOf<RecipeError>()
        fun fail(where: String, message: String) {
            errors += RecipeError(where, message)
        }

        if (recipe.string("recipe") != RecipeCatalog.FORMAT) fail("recipe", "must be \"${RecipeCatalog.FORMAT}\"")
        if (recipe.string("id")?.matches(APP_ID) != true) fail("id", "must be lowercase letters, digits and dashes")
        if (recipe.string("title").isNullOrBlank()) fail("title", "missing")

        val permissions = (recipe["permissions"] as? JsonArray)?.mapNotNull { it.stringOrNull() }?.toSet()
        if (permissions == null) fail("permissions", "must be a list")
        for (permission in permissions.orEmpty()) {
            if (permission !in RecipeCatalog.permissions) fail("permissions", "unknown permission $permission")
        }

        val data = recipe["data"] as? JsonObject
        if (data.isNullOrEmpty()) fail("data", "must name at least one data source")
        for ((key, source) in data.orEmpty()) {
            val provider = RecipeCatalog.providers[(source as? JsonObject)?.string("provider")]
            if (provider == null) fail("data.$key", "unknown provider")
            else if (provider.permission !in permissions.orEmpty()) fail("data.$key", "needs permission ${provider.permission}")
        }
        val dataKeys = data.orEmpty().keys

        when (val rules = recipe["rules"]) {
            null -> Unit
            is JsonArray -> rules.forEachIndexed { i, rule -> checkRule(rule)?.let { fail("rules[$i]", it) } }
            else -> fail("rules", "must be a list")
        }

        val components = recipe["components"] as? JsonArray
        if (components.isNullOrEmpty()) {
            fail("components", "must be a non-empty list")
            return errors
        }
        if (components.size > RecipeCatalog.MAX_COMPONENTS) fail("components", "more than ${RecipeCatalog.MAX_COMPONENTS} components")
        val byId = linkedMapOf<String, JsonObject>()
        components.forEachIndexed { i, element ->
            val component = element as? JsonObject
            val id = component?.string("id")
            when {
                component == null || id == null -> fail("components[$i]", "must be an object with an id")
                id in byId -> fail(id, "duplicate id")
                else -> byId[id] = component
            }
        }
        for ((id, component) in byId) errors += checkComponent(id, component, byId.keys, dataKeys)
        if (RecipeCatalog.ROOT_ID !in byId) fail("components", "no component has id \"${RecipeCatalog.ROOT_ID}\"")
        else errors += checkTree(byId)
        return errors
    }

    private fun checkComponent(id: String, component: JsonObject, ids: Set<String>, dataKeys: Set<String>): List<RecipeError> {
        val spec = RecipeCatalog.components[component.string("component")]
            ?: return listOf(RecipeError(id, "unknown component ${component["component"]}"))
        val unknown = component.keys
            .filter { it !in RecipeCatalog.COMPONENT_KEYS && it !in spec.props }
            .map { RecipeError(id, "${spec.name} has no prop $it") }
        val bad = spec.props.mapNotNull { (name, prop) ->
            val value = component[name]
            val problem = if (value == null) "missing".takeIf { prop.required } else checkProp(prop, value, ids, dataKeys)
            problem?.let { RecipeError("$id.$name", it) }
        }
        return unknown + bad
    }

    private fun checkProp(prop: PropSpec, value: JsonElement, ids: Set<String>, dataKeys: Set<String>): String? = when (prop.kind) {
        PropKind.TEXT -> "must be text".takeIf { value.stringOrNull().isNullOrBlank() }
        PropKind.NUMBER -> "must be a number".takeIf { value !is JsonPrimitive || value.isString || value.doubleOrNull == null }
        PropKind.ENUM -> "must be one of ${prop.values.joinToString()}".takeIf { value.stringOrNull() !in prop.values }
        PropKind.PATH -> checkPath(value.stringOrNull(), dataKeys)
        PropKind.ID -> "unknown component id".takeIf { value.stringOrNull() !in ids }
        PropKind.ID_LIST -> when {
            value !is JsonArray -> "must be a list of component ids"
            value.isEmpty() -> "must not be empty"
            value.any { it.stringOrNull() !in ids } -> "points at an unknown component id"
            else -> null
        }
        PropKind.FUNCTION -> checkFunction(value as? JsonObject, dataKeys)
        PropKind.ACTION -> {
            val action = (value as? JsonObject)?.string("action")
            if (action in RecipeCatalog.actions) null else "unknown action $action"
        }
    }

    private fun checkPath(path: String?, dataKeys: Set<String>): String? = when {
        path == null || !path.startsWith("/") -> "must be a path like /${dataKeys.firstOrNull() ?: "items"}"
        path.removePrefix("/").substringBefore('/') !in dataKeys -> "unknown data key in $path"
        else -> null
    }

    private fun checkFunction(fn: JsonObject?, dataKeys: Set<String>): String? {
        if (fn == null) return "must be a function like {\"fn\": \"count\", \"of\": \"/items\"}"
        val name = fn.string("fn")
        if (name !in RecipeCatalog.functions) return "unknown function $name"
        checkPath(fn.string("of"), dataKeys)?.let { return "of $it" }
        val period = fn["period"] ?: return null
        return "period must be one of ${RecipeCatalog.periods.joinToString()}".takeIf { period.stringOrNull() !in RecipeCatalog.periods }
    }

    private fun checkRule(rule: JsonElement): String? {
        val obj = rule as? JsonObject ?: return "must be an object"
        val condition = obj["when"] as? JsonObject ?: return "needs a \"when\" object"
        if (condition.string("op") !in RecipeCatalog.RULE_OPS) return "op must be one of ${RecipeCatalog.RULE_OPS.joinToString()}"
        if (condition.string("field").isNullOrBlank() || condition.string("value").isNullOrBlank()) return "needs field and value"
        val set = obj["set"] as? JsonObject
        if (set.isNullOrEmpty() || set.values.any { it.stringOrNull() == null }) return "needs a \"set\" object of text values"
        return null
    }

    /** Walks from root. A reference back to an ancestor is a loop; a component root never reaches is an error. */
    private fun checkTree(byId: Map<String, JsonObject>): List<RecipeError> {
        val errors = mutableListOf<RecipeError>()
        val reached = mutableSetOf<String>()
        fun visit(id: String, ancestors: Set<String>) {
            if (!reached.add(id)) return
            val component = byId[id] ?: return
            val spec = RecipeCatalog.components[component.string("component")] ?: return
            for (ref in spec.refs(component)) {
                if (ref == id || ref in ancestors) errors.add(RecipeError(id, "points back at $ref (a loop)"))
                else visit(ref, ancestors + id)
            }
        }
        visit(RecipeCatalog.ROOT_ID, emptySet())
        for (id in byId.keys) if (id !in reached) errors.add(RecipeError(id, "not reachable from root"))
        return errors
    }
}
