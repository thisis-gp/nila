package com.nila.launcher.skills

import kotlinx.serialization.json.JsonArray
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
}
