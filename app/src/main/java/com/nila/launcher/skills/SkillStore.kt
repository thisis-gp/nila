package com.nila.launcher.skills

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class InstalledApp(val id: String, val title: String, val recipe: JsonObject, val grants: Set<String>)

sealed interface InstallResult {
    data class Installed(val app: InstalledApp) : InstallResult
    data class Invalid(val errors: List<RecipeError>) : InstallResult
}

/**
 * The apps the user has installed, with the permissions they granted each one. Recipes are validated again here, so
 * nothing reaches the runtime that the validator has not passed, whether it came from the App Builder or a file.
 */
class SkillStore(dir: File) {
    private val registry = FileAppStore(File(dir, REGISTRY_DIR))
    /** Every app's own data. Each app can only name stores pinned to its id (RecipeValidator). */
    val data = FileAppStore(File(dir, DATA_DIR))

    fun list(): List<InstalledApp> = registry.list(APPS).mapNotNull(::appOf)

    fun get(id: String): InstalledApp? = list().firstOrNull { it.id == id }

    /** The installed app whose title appears in [text], e.g. "open reading log"; the longest title wins. */
    fun find(text: String): InstalledApp? {
        val said = text.lowercase()
        return list().filter { said.contains(it.title.lowercase()) || said.contains(it.id.replace('-', ' ')) }
            .maxByOrNull { it.title.length }
    }

    fun install(recipe: JsonObject, grants: Set<String>): InstallResult {
        val errors = RecipeValidator.validate(recipe)
        if (errors.isNotEmpty()) return InstallResult.Invalid(errors)
        val taken = list().map { it.id }.toSet()
        val baseId = recipe.string("id").orEmpty()
        val id = if (baseId !in taken) baseId else generateSequence(FIRST_COPY) { it + 1 }.map { "$baseId-$it" }.first { it !in taken }
        // A new id moves the app's data under its own store names (RecipeRepair pins stores to the id).
        val final = if (id == baseId) recipe else RecipeRepair.repair(JsonObject(recipe + ("id" to JsonPrimitive(id))))
        val finalErrors = RecipeValidator.validate(final)
        if (finalErrors.isNotEmpty()) return InstallResult.Invalid(finalErrors)
        val asked = (final["permissions"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.toSet()
        val app = InstalledApp(id, final.string("title").orEmpty(), final, grants intersect asked)
        registry.replaceAll(APPS, registry.list(APPS) + itemOf(app))
        return InstallResult.Installed(app)
    }

    fun revoke(id: String, permission: String) {
        val app = get(id) ?: return
        save(app.copy(grants = app.grants - permission))
    }

    fun uninstall(id: String): Boolean {
        val app = get(id) ?: return false
        (app.recipe["data"] as? JsonObject).orEmpty().values.mapNotNull { (it as? JsonObject)?.string(STORE) }.forEach(data::drop)
        return registry.delete(APPS, id)
    }

    private fun save(app: InstalledApp) {
        registry.replaceAll(APPS, registry.list(APPS).map { if (it.id == app.id) itemOf(app) else it })
    }

    private fun itemOf(app: InstalledApp) = Item(app.id, mapOf(RECIPE to app.recipe, GRANTS to JsonArray(app.grants.sorted().map { JsonPrimitive(it) })))

    private fun appOf(item: Item): InstalledApp? {
        val recipe = item.fields[RECIPE] as? JsonObject ?: return null
        val grants = (item.fields[GRANTS] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.toSet()
        return InstalledApp(item.id, recipe.string("title").orEmpty(), recipe, grants)
    }

    private companion object {
        const val REGISTRY_DIR = "registry"
        const val DATA_DIR = "data"
        const val APPS = "apps"
        const val RECIPE = "recipe"
        const val GRANTS = "grants"
        const val STORE = "store"
        const val FIRST_COPY = 2
    }
}
