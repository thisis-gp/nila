package com.nila.launcher.skills

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One saved thing in an app: a book, a glass of water, a payment. Field values stay JSON so recipes decide their shape. */
data class Item(val id: String, val fields: Map<String, JsonElement>) {
    fun string(field: String): String? = fields[field].stringOrNull()

    /** Null for missing fields and for text that only looks like a number, so sums never count "12" typed as text. */
    fun number(field: String): Double? = (fields[field] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    fun toJson(): JsonObject = JsonObject(fields + (ID to JsonPrimitive(id)))

    companion object {
        const val ID = "id"

        fun fromJson(json: JsonObject): Item? = json.string(ID)?.let { Item(it, json - ID) }
    }
}
