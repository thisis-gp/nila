package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBuilderTest {
    /** Replies in order and records what NILA asked. */
    private class ScriptedModel(vararg replies: String) : RecipeModel {
        val requests = mutableListOf<String>()
        val schemas = mutableListOf<String?>()
        private val queue = ArrayDeque(replies.toList())
        override fun complete(system: String, user: String, schema: String?): String {
            requests += user
            schemas += schema
            return queue.removeFirst()
        }
    }

    private val waterPlan = """{"template":"counter","title":"Water","providers":["storage"]}"""
    private val waterScreen = """{"components":[{"id":"root","component":"Counter","label":"Glasses today",
        "value":{"fn":"count","of":"/items","period":"day"},"onStep":{"action":"counter.step"}}]}"""

    @Test
    fun everyTemplateHasAValidExample() {
        assertEquals(RecipeCatalog.templates.toSet(), RecipeExamples.all.keys)
        for ((template, example) in RecipeExamples.all) {
            assertEquals(template, emptyList<RecipeError>(), RecipeValidator.validate(Json.parseToJsonElement(example).jsonObject))
        }
    }

    @Test
    fun promptsNameEveryComponentAndTemplate() {
        RecipeCatalog.components.keys.forEach { assertTrue(it, it in AppBuilderPrompts.catalogText()) }
        (RecipeCatalog.templates + RecipeCatalog.NO_TEMPLATE).forEach { assertTrue(it, "- $it:" in AppBuilderPrompts.PLAN_SYSTEM) }
    }

    @Test
    fun buildsOnTheFirstTry() {
        val result = AppBuilder(ScriptedModel(waterPlan, waterScreen)).build("count my water") as BuildResult.Built
        assertEquals(1, result.attempts)
        assertEquals("water", result.recipe.string("id"))
        assertEquals(listOf("storage.local"), result.recipe.permissionList())
    }

    @Test
    fun retriesWithTheValidatorsErrors() {
        val broken = """{"components":[{"id":"root","component":"Counter","label":"Glasses",
            "value":{"fn":"average","of":"/items"},"onStep":{"action":"counter.step"}}]}"""
        val model = ScriptedModel(waterPlan, broken, waterScreen)
        val result = AppBuilder(model).build("count my water") as BuildResult.Built
        assertEquals(2, result.attempts)
        assertTrue(model.requests.last(), "root.value: unknown function average" in model.requests.last())
    }

    @Test
    fun fallsBackToTheTemplateAfterThreeBadAnswers() {
        val model = ScriptedModel(waterPlan, "nope", "", """{"components":[]}""")
        val result = AppBuilder(model).build("count my water") as BuildResult.Fallback
        assertEquals("Water", result.recipe.string("title"))
        assertEquals("water", result.recipe.string("id"))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(result.recipe))
        assertTrue("answer: was not a JSON object" in model.requests[2])
    }

    @Test
    fun saysPlainlyWhatItCannotBuild() {
        val result = AppBuilder(ScriptedModel("""{"template":"none","title":"Uber","providers":[]}""")).build("book an uber")
        assertEquals(BuildResult.Gap(AppBuilder.CANNOT_BUILD), result)
    }

    @Test
    fun anUnreadablePlanIsAGap() {
        assertEquals(BuildResult.Gap(AppBuilder.UNCLEAR), AppBuilder(ScriptedModel("I think you want a tracker")).build("hmm"))
    }

    @Test
    fun moneyAppsReadTransactionsAndLearnRules() {
        val plan = """{"template":"money","title":"Expenses","providers":["transactions","rules"]}"""
        val example = Json.parseToJsonElement(RecipeExamples.forTemplate("money")).jsonObject
        val screen = JsonObject(mapOf("components" to example.getValue("components"), "rules" to example.getValue("rules"))).toString()
        val result = AppBuilder(ScriptedModel(plan, screen)).build("expense tracker from my bank sms") as BuildResult.Built
        assertEquals(setOf("txns", "rules"), (result.recipe.getValue("data") as JsonObject).keys)
        assertEquals(listOf("storage.local", "transactions.read"), result.recipe.permissionList())
    }

    @Test
    fun slugsAnyTitleIntoAValidId() {
        assertEquals("goa-trip", slug("Goa Trip!!"))
        assertEquals("app", slug("தண்ணீர்"))
        assertTrue(slug("x".repeat(100)).length <= RecipeCatalog.MAX_ID_CHARS)
    }

    @Test
    fun fallbackDataBelongsToTheNewApp() {
        val plan = """{"template":"log","title":"Weight Log","providers":["storage"]}"""
        val result = AppBuilder(ScriptedModel(plan, "nope", "nope", "nope")).build("track my weight") as BuildResult.Fallback
        val items = (result.recipe.getValue("data") as JsonObject).getValue("items") as JsonObject
        assertEquals("weight-log-storage", items.string("store"))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(result.recipe))
    }

    @Test
    fun fillPromptShowsOnlyTheScreenAsCompactJson() {
        // Gemma copies the example's shape: a full pretty-printed recipe made it echo ~1.5k chars and hit the token cap.
        val prompt = AppBuilderPrompts.fillSystem(RecipeExamples.forTemplate("money"))
        assertTrue(prompt, "\"components\":[{\"id\":\"root\"" in prompt)
        assertTrue(prompt, "\"permissions\"" !in prompt && "\"recipe\"" !in prompt)
    }

    @Test
    fun onlyTheScreenIsConstrainedToTheCatalogSchema() {
        val model = ScriptedModel(waterPlan, waterScreen)
        AppBuilder(model).build("count my water")
        // The plan stays free text: its title has spaces, which LiteRT-LM #3510 can break under constraints on device.
        assertEquals(listOf(null, RecipeSchema.FILL), model.schemas)
    }
}

