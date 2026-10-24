package com.nila.launcher.skills

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeValidatorTest {
    private fun messages(recipe: JsonObject) = RecipeValidator.validate(recipe).map { it.toString() }

    private fun assertHas(errors: List<String>, expected: String) =
        assertTrue("expected \"$expected\" in $errors", errors.any { expected in it })

    @Test
    fun acceptsAMinimalRecipe() {
        assertEquals(emptyList<String>(), messages(recipe("""[{"id":"root","component":"Text","text":"Hi"}]""")))
    }

    @Test
    fun rejectsUnknownComponentsAndProps() {
        val errors = messages(recipe("""[{"id":"root","component":"Column","children":["a","b"]},
            {"id":"a","component":"VideoPlayer"},{"id":"b","component":"Text","text":"x","color":"red"}]"""))
        assertHas(errors, "a: unknown component")
        assertHas(errors, "b: Text has no prop color")
    }

    @Test
    fun rejectsMissingRootDanglingIdsAndLoops() {
        assertHas(messages(recipe("""[{"id":"main","component":"Text","text":"x"}]""")), "no component has id \"root\"")
        assertHas(messages(recipe("""[{"id":"root","component":"Column","children":["ghost"]}]""")), "root.children: points at an unknown component id")
        assertHas(
            messages(recipe("""[{"id":"root","component":"Column","children":["a"]},{"id":"a","component":"Column","children":["root"]}]""")),
            "a: points back at root",
        )
    }

    @Test
    fun rejectsComponentsRootNeverReaches() {
        val errors = messages(recipe("""[{"id":"root","component":"Text","text":"x"},{"id":"lost","component":"Text","text":"y"}]"""))
        assertHas(errors, "lost: not reachable from root")
    }

    @Test
    fun emptyContainersAreInvalid() {
        assertHas(messages(recipe("""[{"id":"root","component":"Column","children":[]}]""")), "root.children: must not be empty")
        assertHas(messages(recipe("""[]""")), "components: must be a non-empty list")
    }

    @Test
    fun checksPathsFunctionsAndActions() {
        val errors = messages(recipe("""[{"id":"root","component":"Column","children":["t","b"]},
            {"id":"t","component":"StatTile","label":"x","value":{"fn":"average","of":"/items"}},
            {"id":"b","component":"Button","label":"Go","onClick":{"action":"http.post"}}]"""))
        assertHas(errors, "t.value: unknown function average")
        assertHas(errors, "b.onClick: unknown action http.post")
        assertHas(messages(recipe("""[{"id":"root","component":"Chart","kind":"donut","data":"/txns"}]""")), "root.data: unknown data key in /txns")
    }

    @Test
    fun dataSourcesNeedTheirPermission() {
        val errors = messages(recipe(
            """[{"id":"root","component":"Text","text":"x"}]""",
            data = """{"txns":{"provider":"transactions"}}""",
            permissions = """["storage.local"]""",
        ))
        assertHas(errors, "data.txns: needs permission transactions.read")
    }

    @Test
    fun rejectsRulesOutsideTheWhitelist() {
        val errors = messages(recipe(
            """[{"id":"root","component":"Text","text":"x"}]""",
            extra = """"rules":[{"when":{"op":"regex","field":"merchant","value":".*"},"set":{"category":"Food"}}],""",
        ))
        assertHas(errors, "rules[0]: op must be one of")
    }

    @Test
    fun eachComponentHasOnePlace() {
        // A component listed twice, or by two parents, renders twice; chained, that doubles per level.
        assertHas(
            messages(recipe("""[{"id":"root","component":"Column","children":["b","b"]},{"id":"b","component":"Text","text":"x"}]""")),
            "b: used in more than one place",
        )
        assertHas(
            messages(recipe("""[{"id":"root","component":"Column","children":["a","c"]},{"id":"a","component":"Column","children":["d"]},
                {"id":"c","component":"Column","children":["d"]},{"id":"d","component":"Text","text":"x"}]""")),
            "d: used in more than one place",
        )
    }

    @Test
    fun ownStoresBelongToTheApp() {
        val errors = messages(recipe(
            """[{"id":"root","component":"Text","text":"x"}]""",
            data = """{"items":{"provider":"storage","store":"bank-app-storage"}}""",
        ))
        assertHas(errors, "data.items: store must be test-app-storage")
    }
}
