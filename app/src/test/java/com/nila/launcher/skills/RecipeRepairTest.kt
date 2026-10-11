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

    // Patterns seen in the PC eval of Gemma 4 E2B (ledger, Task 6).

    @Test
    fun closesJsonTheModelLeftOpen() {
        val fixed = RecipeRepair.extractJson("""{"components":[{"id":"root","component":"Text","text":"x"}]""")
        assertEquals(1, (fixed?.get("components") as JsonArray).size)
        assertNull(RecipeRepair.extractJson("""{"components":[{"id":"root","text":"unfinished"""))
    }

    @Test
    fun addsTheRowAListPointsAtButNeverDefined() {
        val items = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["form","list"]},
            {"id":"form","component":"Form","fields":["t"],"submit":{"action":"storage.add"}},
            {"id":"t","component":"Input","label":"Title","field":"title","type":"text"},
            {"id":"list","component":"List","items":"/items","template":"row"}]"""))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(items))
        val row = items.components().single { it.string("id") == "row" }
        assertEquals("ItemRow" to "title", row.string("component") to row.string("titleField"))

        val txns = RecipeRepair.repair(recipe(
            """[{"id":"root","component":"List","items":"/txns","template":"row"}]""",
            data = """{"txns":{"provider":"transactions"}}""",
            permissions = """["transactions.read"]""",
        ))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(txns))
        assertEquals("TxnRow", txns.components().single { it.string("id") == "row" }.string("component"))
    }

    @Test
    fun splitsAFieldWrittenIntoAPath() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"StatTile","label":"Sleep",
            "value":{"fn":"sum","of":"/items.sleepHours","period":"week"}}]"""))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
        val value = fixed.components().single().getValue("value") as JsonObject
        assertEquals("/items" to "sleepHours", value.string("of") to value.string("field"))
    }

    @Test
    fun dropsOptionalPropsWithValuesOutsideTheirList() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Text","text":"x","style":"heading"}]"""))
        assertNull(fixed.components().single()["style"])
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
    }

    @Test
    fun addsTheInputsAFormPointsAtButNeverDefined() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["form","list"]},
            {"id":"form","component":"Form","fields":["item"],"submit":{"action":"storage.add"}},
            {"id":"list","component":"Checklist","items":"/items","labelField":"item"}]"""))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
        val input = fixed.components().single { it.string("id") == "item" }
        assertEquals(listOf("Input", "item", "text"), listOf(input.string("component"), input.string("field"), input.string("type")))
    }

    @Test
    fun dropsTextWithNothingToSay() {
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["header","body"]},
            {"id":"header","component":"Text","style":"title"},{"id":"body","component":"Text","text":"hi"}]"""))
        assertEquals(listOf("root", "body"), fixed.components().map { it.string("id") })
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
    }

    @Test
    fun givesAChecklistOfTheUsersOwnItemsAnAddForm() {
        // PC eval v5: three checklists came back with no way to add items, and Gemma repeated that on every retry.
        val fixed = RecipeRepair.repair(recipe("""[{"id":"root","component":"Column","children":["title","list"]},
            {"id":"title","component":"Text","text":"Habits"},
            {"id":"list","component":"Checklist","items":"/items","labelField":"habit","onToggle":{"action":"storage.update"}}]"""))
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(fixed))
        assertEquals(emptyList<RecipeError>(), RecipeCompleteness.check(fixed))
        val root = fixed.components().first()
        assertEquals(listOf("title", "list-add", "list"), (root.getValue("children") as JsonArray).map { it.stringOrNull() })
        val input = fixed.components().single { it.string("component") == "Input" }
        assertEquals("habit", input.string("field"))
    }
}

