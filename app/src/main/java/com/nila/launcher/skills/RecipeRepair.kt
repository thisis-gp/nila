package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Deterministic fixes for small-model mistakes, applied before validation. It removes, renames and fills only
 * structural defaults (closing brackets, a list's missing row); it never invents what the user sees, so a recipe
 * that is still wrong goes back to the model with the validator's errors.
 */
object RecipeRepair {
    /** Longer output means the model ran away (LiteRT-LM #3510); treat it as no answer. */
    const val MAX_RAW_CHARS = 16_000
    private const val MAX_NAME_DISTANCE = 3
    /** "Stat" may mean StatTile; "C" means nothing. */
    private const val MIN_PREFIX_CHARS = 4
    private val FENCE = Regex("```[a-zA-Z]*")
    private const val LIST = "List"
    private const val INPUT = "Input"
    private const val TEXT = "Text"
    private const val FORM = "Form"
    private const val DEFAULT_INPUT_TYPE = "text"
    private const val TXN_ROW = "TxnRow"
    private const val ITEM_ROW = "ItemRow"
    private const val TRANSACTIONS = "transactions"
    private const val DEFAULT_TITLE_FIELD = "name"

    fun extractJson(raw: String): JsonObject? {
        if (raw.length > MAX_RAW_CHARS) return null
        val text = raw.replace(FENCE, "").trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end > start) parse(text.substring(start, end + 1))?.let { return it }
        // Gemma often stops one or two closing brackets short.
        return closeBrackets(text.substring(start))?.let(::parse)
    }

    private fun parse(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

    /** Appends the brackets still open at the end, or null when the text stops inside a string or brackets mismatch. */
    private fun closeBrackets(json: String): String? {
        val open = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '{' -> open.addLast('}')
                c == '[' -> open.addLast(']')
                c == '}' || c == ']' -> if (open.removeLastOrNull() != c) return null
            }
        }
        if (inString || open.isEmpty()) return null
        return json.trimEnd().removeSuffix(",") + open.reversed().joinToString("")
    }

    fun repair(recipe: JsonObject): JsonObject {
        val appId = recipe.string("id").orEmpty()
        val data = (recipe["data"] as? JsonObject).orEmpty()
            .filterValues { RecipeCatalog.providers[(it as? JsonObject)?.string("provider")] != null }
            .mapValues { (_, source) -> pinStore(source as JsonObject, appId) }
        // Least privilege: exactly the permissions the data sources need, whatever the model asked for.
        val permissions = data.values.mapNotNull { RecipeCatalog.providers[(it as JsonObject).string("provider")]?.permission }.toSortedSet()
        return JsonObject(
            recipe + mapOf(
                "recipe" to JsonPrimitive(RecipeCatalog.FORMAT),
                "permissions" to JsonArray(permissions.map { JsonPrimitive(it) }),
                "data" to JsonObject(data),
                "components" to JsonArray(repairComponents((recipe["components"] as? JsonArray).orEmpty(), data).map { JsonObject(it) }),
            ),
        )
    }

    private fun pinStore(source: JsonObject, appId: String): JsonObject {
        val provider = RecipeCatalog.providers.getValue(source.string("provider").orEmpty())
        if (!provider.ownStore) return source
        return JsonObject(source + ("store" to JsonPrimitive(RecipeCatalog.storeName(appId, provider.name))))
    }

    private fun repairComponents(raw: List<JsonElement>, data: Map<String, JsonElement>): List<Map<String, JsonElement>> {
        val byId = linkedMapOf<String, MutableMap<String, JsonElement>>()
        for (element in raw) {
            val component = element as? JsonObject ?: continue
            val id = component.string("id") ?: continue
            val spec = nearestComponent(component.string("component")) ?: continue
            if (id in byId) continue
            byId[id] = cleanProps(component, spec)
        }
        // A Text with nothing to say is a heading the model forgot to write; its parent loses the reference below.
        byId.values.removeIf { it.string("component") == TEXT && it.string("text").isNullOrBlank() }
        addMissingRows(byId, data)
        addMissingInputs(byId)
        for (component in byId.values) dropDanglingRefs(component, byId.keys)
        val rooted = if (RecipeCatalog.ROOT_ID in byId) byId else withRoot(byId)
        return keepTree(rooted)
    }

    private fun cleanProps(component: JsonObject, spec: ComponentSpec): MutableMap<String, JsonElement> {
        val clean = component.filterKeys { it in RecipeCatalog.COMPONENT_KEYS || it in spec.props }.toMutableMap()
        clean["component"] = JsonPrimitive(spec.name)
        for ((name, prop) in spec.props) {
            val value = clean[name] ?: continue
            when (prop.kind) {
                PropKind.ENUM -> if (!prop.required && value.stringOrNull() !in prop.values) clean.remove(name)
                PropKind.PATH -> value.stringOrNull()?.let { clean[name] = JsonPrimitive(splitPath(it).first) }
                PropKind.FUNCTION -> (value as? JsonObject)?.let { clean[name] = splitFunctionPath(it) }
                else -> {}
            }
        }
        return clean
    }

    /** "/items.sleepHours" is how Gemma writes path "/items" with field "sleepHours". */
    private fun splitPath(path: String): Pair<String, String?> {
        val dot = path.indexOf('.')
        return if (dot < 0) path to null else path.substring(0, dot) to path.substring(dot + 1).ifBlank { null }
    }

    private fun splitFunctionPath(fn: JsonObject): JsonObject {
        val (path, field) = splitPath(fn.string("of") ?: return fn)
        if (field == null) return fn
        val withField = if (fn["field"] == null) mapOf("field" to JsonPrimitive(field)) else emptyMap()
        return JsonObject(fn + mapOf("of" to JsonPrimitive(path)) + withField)
    }

    /** A List whose row was named but never written gets the plain row for its data, so the list can still render. */
    private fun addMissingRows(byId: MutableMap<String, MutableMap<String, JsonElement>>, data: Map<String, JsonElement>) {
        for (list in byId.values.filter { it.string("component") == LIST }) {
            val rowId = list.string("template")?.takeIf { it !in byId } ?: continue
            val key = list.string("items")?.removePrefix("/")?.substringBefore('/')
            val provider = (data[key] as? JsonObject)?.string("provider")
            val row = mutableMapOf<String, JsonElement>("id" to JsonPrimitive(rowId))
            if (provider == TRANSACTIONS) {
                row["component"] = JsonPrimitive(TXN_ROW)
            } else {
                val titleField = byId.values.firstOrNull { it.string("component") == INPUT }?.string("field") ?: DEFAULT_TITLE_FIELD
                row["component"] = JsonPrimitive(ITEM_ROW)
                row["titleField"] = JsonPrimitive(titleField)
            }
            byId[rowId] = row
        }
    }

    /** A Form field id that was never written becomes a plain text input named after it. */
    private fun addMissingInputs(byId: MutableMap<String, MutableMap<String, JsonElement>>) {
        val missing = byId.values.filter { it.string("component") == FORM }
            .flatMap { form -> (form["fields"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() } }
            .filter { it !in byId }
        for (id in missing.distinct()) {
            byId[id] = mutableMapOf(
                "id" to JsonPrimitive(id),
                "component" to JsonPrimitive(INPUT),
                "label" to JsonPrimitive(id),
                "field" to JsonPrimitive(id),
                "type" to JsonPrimitive(DEFAULT_INPUT_TYPE),
            )
        }
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

    /** Keeps what root reaches, in the original order. Cuts references back to an ancestor and second uses of a component. */
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
                        if (ref in path || ref in reached) component.remove(name) else visit(ref, path)
                    }
                    PropKind.ID_LIST -> {
                        val kept = mutableListOf<String>()
                        for (ref in (component[name] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }) {
                            if (ref in path || ref in reached) continue
                            kept += ref
                            visit(ref, path)
                        }
                        component[name] = idList(kept)
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
