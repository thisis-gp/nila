package com.nila.launcher.skills

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeRepairTest {
    @Test
    fun extractsJsonFromFencesAndProse() {
        val raw = "Sure! Here is your app:\n```json\n{\"components\": []}\n```\nEnjoy."
        assertEquals(JsonArray(emptyList()), RecipeRepair.extractJson(raw)?.get("components"))
    }

    @Test
    fun rejectsGarbageAndRunawayOutput() {
        assertNull(RecipeRepair.extractJson("no json here"))
        assertNull(RecipeRepair.extractJson("{\"a\": "))
        assertNull(RecipeRepair.extractJson("{\"a\":\"" + "x".repeat(RecipeRepair.MAX_RAW_CHARS) + "\"}"))
    }

    @Test
    fun mapsNearComponentNamesAndDropsUnknownOnes() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["t","l","v"]},
            {"id":"t","component":"Statile","label":"n","value":{"fn":"count","of":"/items"}},
            {"id":"l","component":"ListView","items":"/items","template":"r"},
            {"id":"r","component":"ItemRow","titleField":"name"},
            {"id":"v","component":"VideoPlayer"}]"""))
        val names = fixed.components().associate { it.string("id") to it.string("component") }
        assertEquals(mapOf("root" to "Column", "t" to "StatTile", "l" to "List", "r" to "ItemRow"), names)
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
    }

    @Test
    fun dropsUnknownPropsAndRecomputesPermissions() {
        val fixed = RecipeRepair.repair(recipe(
            """[{"id":"root","component":"Text","text":"x","color":"red"}]""",
            permissions = """["storage.local","network.all"]""",
        ))
        assertNull(fixed.components().single()["color"])
        assertEquals(listOf("storage.local"), fixed.permissionList())
        assertEquals("nila.app/1", fixed.string("recipe"))
    }

    @Test
    fun addsRootWhenMissing() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"title","component":"Text","text":"x"},
            {"id":"btn","component":"Button","label":"Add","onClick":{"action":"storage.add"}}]"""))
        val root = fixed.components().first()
        assertEquals("root", root.string("id"))
        assertEquals(listOf("title", "btn"), (root.getValue("children") as JsonArray).map { it.stringOrNull() })
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
    }

    @Test
    fun breaksLoopsAndDropsOrphans() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["a"]},
            {"id":"a","component":"Column","children":["root","b"]},
            {"id":"b","component":"Text","text":"x"},
            {"id":"orphan","component":"Text","text":"y"}]"""))
        assertEquals(listOf("root", "a", "b"), fixed.components().map { it.string("id") })
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
    }

    @Test
    fun editDistanceCountsSingleEdits() {
        assertEquals(1, editDistance("statile", "stattile"))
        assertEquals(0, editDistance("list", "list"))
    }

    @Test
    fun keepsEachComponentInOnePlace() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["a","b","b"]},
            {"id":"a","component":"Text","text":"x"},{"id":"b","component":"Text","text":"y"}]"""))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
        assertEquals(listOf("a", "b"), (fixed.components().first().getValue("children") as JsonArray).map { it.stringOrNull() })
        // A child shared by two parents stays with the first; the second parent, now empty, goes back to the model.
        val shared = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["a","c"]},
            {"id":"a","component":"Column","children":["d"]},{"id":"c","component":"Column","children":["d"]},
            {"id":"d","component":"Text","text":"x"}]"""))
        assertEquals(listOf("c.children: must not be empty"), RecipeValidator.validate(shared).map { it.toString() })
    }

    @Test
    fun pinsStoresToTheApp() {
        val fixed = RecipeRepair.repair(recipe(
            """[{"id":"root","component":"Text","text":"x"}]""",
            data = """{"items":{"provider":"storage","store":"bank-app-storage"},"txns":{"provider":"transactions"}}""",
            permissions = """["storage.local","transactions.read"]""",
        ))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
        val data = fixed.getValue("data") as JsonObject
        assertEquals("test-app-storage", (data.getValue("items") as JsonObject).string("store"))
        assertEquals(null, (data.getValue("txns") as JsonObject).string("store"))
    }
}
