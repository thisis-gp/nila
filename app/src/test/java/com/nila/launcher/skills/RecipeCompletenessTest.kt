package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeCompletenessTest {
    private fun problems(components: String, data: String = """{"items":{"provider":"storage","store":"test-app-storage"}}""") =
        RecipeCompleteness.check(recipe(components, data = data)).map { it.toString() }

    @Test
    fun aStorageAppMustLetTheUserAddAndSeeItems() {
        assertEquals(
            listOf(
                "data.items: nothing adds to /items; add a Form or Button with action storage.add, or a Counter",
                "data.items: nothing shows /items; add a List, Checklist, Chart, StatTile or Counter that reads /items",
            ),
            problems("""[{"id":"root","component":"Text","text":"x"}]"""),
        )
    }

    @Test
    fun aFormWithNoListLeavesItemsUnseen() {
        assertEquals(
            listOf("data.items: nothing shows /items; add a List, Checklist, Chart, StatTile or Counter that reads /items"),
            problems("""[{"id":"root","component":"Form","fields":["t"],"submit":{"action":"storage.add"}},
                {"id":"t","component":"Input","label":"Item","field":"name","type":"text"}]"""),
        )
    }

    @Test
    fun aTallyWithNoButtonCannotCount() {
        assertEquals(
            listOf("data.items: nothing adds to /items; add a Form or Button with action storage.add, or a Counter"),
            problems("""[{"id":"root","component":"StatTile","label":"Pushups","value":{"fn":"count","of":"/items","period":"day"}}]"""),
        )
    }

    @Test
    fun paymentsOnlyNeedToBeShown() {
        val data = """{"txns":{"provider":"transactions"}}"""
        assertEquals(listOf("data.txns: nothing shows /txns; add a List, Checklist, Chart, StatTile or Counter that reads /txns"), problems("""[{"id":"root","component":"Text","text":"x"}]""", data))
        assertEquals(emptyList<String>(), problems("""[{"id":"root","component":"Chart","kind":"donut","data":"/txns"}]""", data))
    }

    @Test
    fun everyExampleIsComplete() {
        for ((template, example) in RecipeExamples.all) {
            assertEquals(template, emptyList<RecipeError>(), RecipeCompleteness.check(Json.parseToJsonElement(example).jsonObject))
        }
    }
}
