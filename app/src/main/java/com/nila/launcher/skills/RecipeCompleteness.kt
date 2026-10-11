package com.nila.launcher.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Valid is not useful. In the PC eval, Gemma built counters with no way to count and packing lists that never showed
 * their items. Generated apps must show their data and let the user add to what they keep; failures go back to Gemma.
 */
object RecipeCompleteness {
    fun check(recipe: JsonObject): List<RecipeError> {
        val data = (recipe["data"] as? JsonObject).orEmpty()
        val components = (recipe["components"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val shown = components.flatMap(::readKeys).toSet()
        val adds = components.any { component -> component.values.any { (it as? JsonObject)?.string("action") in RecipeCatalog.ADD_ACTIONS } }
        return data.flatMap { (key, source) ->
            val provider = RecipeCatalog.providers[(source as? JsonObject)?.string("provider")] ?: return@flatMap emptyList()
            listOfNotNull(
                RecipeError("data.$key", "nothing adds to /$key; add a Form or Button with action storage.add, or a Counter")
                    .takeIf { provider.userAdds && !adds },
                RecipeError("data.$key", "nothing shows /$key; add a List, Checklist, Chart, StatTile or Counter that reads /$key")
                    .takeIf { provider.mustShow && key !in shown },
            )
        }
    }

    /** Data keys this component reads through its path and function props. */
    private fun readKeys(component: JsonObject): List<String> {
        val spec = RecipeCatalog.components[component.string("component")] ?: return emptyList()
        return spec.props.mapNotNull { (name, prop) ->
            when (prop.kind) {
                PropKind.PATH -> component.string(name)
                PropKind.FUNCTION -> (component[name] as? JsonObject)?.string("of")
                else -> null
            }?.removePrefix("/")?.substringBefore('/')
        }
    }
}
