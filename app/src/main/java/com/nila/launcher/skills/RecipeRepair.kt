package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic fixes for small-model mistakes, applied before validation. It only removes or renames things.
 * It never invents content, so a recipe that is still wrong goes back to the model with the validator's errors.
 */
object RecipeRepair {
    /** Longer output means the model ran away (LiteRT-LM #3510); treat it as no answer. */
    const val MAX_RAW_CHARS = 16_000
    private const val MAX_NAME_DISTANCE = 3
    /** "Stat" may mean StatTile; "C" means nothing. */
    private const val MIN_PREFIX_CHARS = 4
    private val FENCE = Regex("```[a-zA-Z]*")

    fun extractJson(raw: String): JsonObject? {
        if (raw.length > MAX_RAW_CHARS) return null
        val text = raw.replace(FENCE, "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
    }

    fun repair(recipe: JsonObject): JsonObject {
        val data = (recipe["data"] as? JsonObject).orEmpty()
            .filterValues { RecipeCatalog.providers[(it as? JsonObject)?.string("provider")] != null }
        // Least privilege: exactly the permissions the data sources need, whatever the model asked for.
        val permissions = data.values.mapNotNull { RecipeCatalog.providers[(it as JsonObject).string("provider")]?.permission }.toSortedSet()
        return JsonObject(
            recipe + mapOf(
                "recipe" to JsonPrimitive(RecipeCatalog.FORMAT),
                "permissions" to JsonArray(permissions.map { JsonPrimitive(it) }),
                "data" to JsonObject(data),
                "components" to JsonArray(repairComponents((recipe["components"] as? JsonArray).orEmpty()).map { JsonObject(it) }),
            ),
        )
    }

    private fun repairComponents(raw: List<JsonElement>): List<Map<String, JsonElement>> {
        val byId = linkedMapOf<String, MutableMap<String, JsonElement>>()
        for (element in raw) {
            val component = element as? JsonObject ?: continue
            val id = component.string("id") ?: continue
            val spec = nearestComponent(component.string("component")) ?: continue
            if (id in byId) continue
            byId[id] = component.filterKeys { it in RecipeCatalog.COMPONENT_KEYS || it in spec.props }.toMutableMap()
                .apply { put("component", JsonPrimitive(spec.name)) }
        }
        for (component in byId.values) dropDanglingRefs(component, byId.keys)
        val rooted = if (RecipeCatalog.ROOT_ID in byId) byId else withRoot(byId)
        return keepTree(rooted)
    }

    private fun specOf(component: Map<String, JsonElement>) = RecipeCatalog.components.getValue(component.string("component").orEmpty())

    private fun idList(ids: List<String>) = JsonArray(ids.map { JsonPrimitive(it) })

    private fun dropDanglingRefs(component: MutableMap<String, JsonElement>, ids: Set<String>) {
        for ((name, prop) in specOf(component).props) {
            when (prop.kind) {
                PropKind.ID -> if (component[name].stringOrNull() !in ids) component.remove(name)
                PropKind.ID_LIST -> component[name] = idList((component[name] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.filter { it in ids })
                else -> {}
            }
        }
    }

    /** No "root": wrap every component nobody points at in a Column, in the model's order. */
    private fun withRoot(byId: Map<String, MutableMap<String, JsonElement>>): Map<String, MutableMap<String, JsonElement>> {
        val referenced = byId.values.flatMap { specOf(it).refs(it) }.toSet()
        val root = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(RecipeCatalog.ROOT_ID),
            "component" to JsonPrimitive(RecipeCatalog.ROOT_COMPONENT),
            "children" to idList(byId.keys.filter { it !in referenced }),
        )
        return linkedMapOf(RecipeCatalog.ROOT_ID to root) + byId
    }

    /** Keeps what root reaches, in the original order, and cuts every reference back to an ancestor. */
    private fun keepTree(byId: Map<String, MutableMap<String, JsonElement>>): List<Map<String, JsonElement>> {
        val reached = mutableSetOf<String>()
        fun visit(id: String, ancestors: Set<String>) {
            val component = byId[id] ?: return
            if (!reached.add(id)) return
            val path = ancestors + id
            for ((name, prop) in specOf(component).props) {
                when (prop.kind) {
                    PropKind.ID -> {
                        val ref = component[name].stringOrNull() ?: continue
                        if (ref in path) component.remove(name) else visit(ref, path)
                    }
                    PropKind.ID_LIST -> {
                        val refs = (component[name] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.filter { it !in path }
                        component[name] = idList(refs)
                        for (ref in refs) visit(ref, path)
                    }
                    else -> {}
                }
            }
        }
        visit(RecipeCatalog.ROOT_ID, emptySet())
        return byId.filterKeys { it in reached }.values.toList()
    }

    private fun nearestComponent(name: String?): ComponentSpec? {
        if (name == null) return null
        RecipeCatalog.components[name]?.let { return it }
        val specs = RecipeCatalog.components.values
        if (name.length >= MIN_PREFIX_CHARS) {
            specs.filter { name.startsWith(it.name, ignoreCase = true) || it.name.startsWith(name, ignoreCase = true) }
                .maxByOrNull { it.name.length }
                ?.let { return it }
        }
        return specs.map { it to editDistance(name.lowercase(), it.name.lowercase()) }
            .filter { it.second <= MAX_NAME_DISTANCE }
            .minByOrNull { it.second }
            ?.first
    }
}

internal fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1)
        current[0] = i
        for (j in 1..b.length) {
            val substitution = if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + substitution)
        }
        previous = current
    }
    return previous[b.length]
}
