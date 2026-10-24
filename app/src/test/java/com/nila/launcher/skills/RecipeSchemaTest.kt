package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeSchemaTest {
    private val schema = Json.parseToJsonElement(RecipeSchema.FILL).jsonObject

    private fun componentSchemas(): Map<String?, JsonObject> {
        val components = (schema.getValue("properties") as JsonObject).getValue("components") as JsonObject
        val choices = (components.getValue("items") as JsonObject).getValue("anyOf") as JsonArray
        return choices.map { it as JsonObject }.associateBy {
            ((it.getValue("properties") as JsonObject).getValue("component") as JsonObject).string("const")
        }
    }

    @Test
    fun offersExactlyTheCatalogsComponents() {
        assertEquals(RecipeCatalog.components.keys, componentSchemas().keys)
    }

    @Test
    fun requiresEachComponentsRequiredPropsAndNothingElse() {
        val statTile = componentSchemas().getValue("StatTile")
        assertEquals(setOf("id", "component", "label", "value"), (statTile.getValue("required") as JsonArray).map { it.stringOrNull() }.toSet())
        assertEquals("false", statTile["additionalProperties"].toString())
    }

    @Test
    fun limitsEnumsAndActionsToTheCatalog() {
        val chart = componentSchemas().getValue("Chart").getValue("properties") as JsonObject
        assertEquals(setOf("donut", "bar", "line"), ((chart.getValue("kind") as JsonObject).getValue("enum") as JsonArray).map { it.stringOrNull() }.toSet())
        val button = componentSchemas().getValue("Button").getValue("properties") as JsonObject
        val action = ((button.getValue("onClick") as JsonObject).getValue("properties") as JsonObject).getValue("action") as JsonObject
        assertEquals(RecipeCatalog.actions, (action.getValue("enum") as JsonArray).map { it.stringOrNull() }.toSet())
    }
}
