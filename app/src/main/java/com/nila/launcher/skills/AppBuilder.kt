package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Text in, text out. A non-null [schema] constrains decoding to that JSON schema. On the phone this is LiteRtRecipeModel. */
fun interface RecipeModel {
    fun complete(system: String, user: String, schema: String?): String
}

data class Plan(val template: String, val title: String, val providers: List<String>)

sealed interface BuildResult {
    data class Built(val plan: Plan, val recipe: JsonObject, val attempts: Int) : BuildResult
    /** The model never produced a valid recipe; [recipe] is the template's example under the user's title. */
    data class Fallback(val plan: Plan, val recipe: JsonObject, val errors: List<RecipeError>) : BuildResult
    /** NILA cannot build this; [reason] is shown to the user as is. */
    data class Gap(val reason: String) : BuildResult
}

/**
 * Idea to recipe, spec §7. Code owns the format, id, title and data sources; Gemma only plans and fills the screen,
 * because Gemma 4 E2B is unreliable at writing a whole recipe in one pass.
 */
class AppBuilder(private val model: RecipeModel) {
    fun build(idea: String): BuildResult {
        val plan = plan(idea) ?: return BuildResult.Gap(UNCLEAR)
        if (plan.template == RecipeCatalog.NO_TEMPLATE) return BuildResult.Gap(CANNOT_BUILD)
        val example = RecipeExamples.forTemplate(plan.template)
        val skeleton = skeleton(plan)
        val system = AppBuilderPrompts.fillSystem(example)
        var request = AppBuilderPrompts.fillRequest(idea, skeleton)
        var errors = emptyList<RecipeError>()
        for (attempt in 1..MAX_ATTEMPTS) {
            val raw = model.complete(system, request, RecipeSchema.FILL)
            val filled = RecipeRepair.extractJson(raw)
            errors = if (filled == null) listOf(NOT_JSON) else {
                val recipe = RecipeRepair.repair(withScreen(skeleton, filled))
                val problems = RecipeValidator.validate(recipe).ifEmpty { RecipeCompleteness.check(recipe) }
                if (problems.isEmpty()) return BuildResult.Built(plan, recipe, attempt)
                problems
            }
            request = AppBuilderPrompts.fixRequest(idea, skeleton, raw, errors)
        }
        // Repair moves the example's data under the new app's own store names.
        val fallback = RecipeRepair.repair(
            JsonObject(Json.parseToJsonElement(example).jsonObject + mapOf("id" to skeleton.getValue("id"), "title" to skeleton.getValue("title"))),
        )
        return BuildResult.Fallback(plan, fallback, errors)
    }

    private fun plan(idea: String): Plan? {
        val json = RecipeRepair.extractJson(model.complete(AppBuilderPrompts.PLAN_SYSTEM, idea, null)) ?: return null
        val template = json.string("template")?.takeIf { it in RecipeCatalog.templates || it == RecipeCatalog.NO_TEMPLATE } ?: return null
        val title = json.string("title")?.trim()?.take(MAX_TITLE_CHARS)?.ifBlank { null } ?: DEFAULT_TITLE
        val providers = (json["providers"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }
            .filter { RecipeCatalog.providers[it] != null }.distinct()
        return Plan(template, title, providers.ifEmpty { listOf(DEFAULT_PROVIDER) })
    }

    companion object {
        /** One try plus the two repairs the spec allows. */
        const val MAX_ATTEMPTS = 3
        const val MAX_TITLE_CHARS = 30
        const val DEFAULT_TITLE = "My App"
        const val DEFAULT_PROVIDER = "storage"
        const val UNCLEAR = "I couldn't work out what app you want. Can you say it another way?"
        const val CANNOT_BUILD = "I can't build that yet. It needs something NILA apps can't do, like going online, using the camera or booking things."
        private val NOT_JSON = RecipeError("answer", "was not a JSON object")
        /** Where each provider's data appears in a recipe, e.g. "/txns". */
        val DATA_KEYS = mapOf("transactions" to "txns", "storage" to "items", "rules" to "rules")
    }
}

private val NOT_SLUG = Regex("[^a-z0-9]+")

internal fun slug(title: String): String =
    title.lowercase().replace(NOT_SLUG, "-").trim('-').take(RecipeCatalog.MAX_ID_CHARS).trim('-').ifBlank { "app" }

/** The parts code owns. The model adds components and rules. */
internal fun skeleton(plan: Plan): JsonObject {
    val id = slug(plan.title)
    return buildJsonObject {
        put("recipe", RecipeCatalog.FORMAT)
        put("id", id)
        put("title", plan.title)
        put("version", "1.0.0")
        putJsonObject("data") {
            for (provider in plan.providers) {
                putJsonObject(AppBuilder.DATA_KEYS.getValue(provider)) {
                    put("provider", provider)
                    if (RecipeCatalog.providers.getValue(provider).ownStore) put("store", RecipeCatalog.storeName(id, provider))
                }
            }
        }
    }
}

private fun withScreen(skeleton: JsonObject, filled: JsonObject): JsonObject = JsonObject(
    skeleton + listOfNotNull(
        "components" to (filled["components"] ?: JsonArray(emptyList())),
        filled["rules"]?.let { "rules" to it },
    ),
)
