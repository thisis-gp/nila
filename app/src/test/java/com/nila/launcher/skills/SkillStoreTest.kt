package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun example(template: String) = Json.parseToJsonElement(RecipeExamples.forTemplate(template)).jsonObject
    private fun installed(result: InstallResult) = (result as InstallResult.Installed).app

    @Test
    fun installsAValidRecipeAndListsIt() {
        val store = SkillStore(folder.root)
        val water = installed(store.install(example("counter"), setOf("storage.local")))
        assertEquals("water", water.id)
        assertEquals(listOf("water"), SkillStore(folder.root).list().map { it.id })
    }

    @Test
    fun refusesAnInvalidRecipeAndWritesNothing() {
        val broken = JsonObject(example("counter") + ("components" to kotlinx.serialization.json.JsonArray(emptyList())))
        val result = SkillStore(folder.root).install(broken, setOf("storage.local"))
        assertTrue(result is InstallResult.Invalid)
        assertEquals(emptyList<InstalledApp>(), SkillStore(folder.root).list())
    }

    @Test
    fun aSecondAppWithTheSameIdGetsItsOwnIdAndData() {
        val store = SkillStore(folder.root)
        store.install(example("counter"), setOf("storage.local"))
        val second = installed(store.install(example("counter"), setOf("storage.local")))
        assertEquals("water-2", second.id)
        val stores = (second.recipe.getValue("data") as JsonObject).values.map { (it as JsonObject).string("store") }
        assertEquals(listOf("water-2-storage"), stores)
        assertEquals(emptyList<RecipeError>(), RecipeValidator.validate(second.recipe))
    }

    @Test
    fun grantsAreLimitedToWhatTheRecipeAsksFor() {
        val app = installed(SkillStore(folder.root).install(example("counter"), setOf("storage.local", "transactions.read")))
        assertEquals(setOf("storage.local"), app.grants)
    }

    @Test
    fun revokingAPermissionSticks() {
        val store = SkillStore(folder.root)
        store.install(example("counter"), setOf("storage.local"))
        store.revoke("water", "storage.local")
        assertEquals(emptySet<String>(), SkillStore(folder.root).get("water")?.grants)
    }

    @Test
    fun uninstallRemovesTheAppAndItsData() {
        val store = SkillStore(folder.root)
        val app = installed(store.install(example("counter"), setOf("storage.local")))
        store.data.add("water-storage", mapOf("date" to JsonPrimitive(1L)))
        assertTrue(store.uninstall(app.id))
        assertNull(store.get("water"))
        assertEquals(emptyList<Item>(), store.data.list("water-storage"))
        assertFalse(store.uninstall(app.id))
    }

    @Test
    fun findsAnAppByItsNameInAnyCase() {
        val store = SkillStore(folder.root)
        store.install(example("log"), setOf("storage.local"))
        assertEquals("reading-log", store.find("open reading LOG")?.id)
        assertNull(store.find("open expenses"))
    }
}
