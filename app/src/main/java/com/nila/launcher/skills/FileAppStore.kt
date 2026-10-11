package com.nila.launcher.skills

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Apps' own data, one JSON file per store under [dir]. Store names come from recipes, which are untrusted, so a name
 * is checked before it becomes a path. Writes go to a temp file and are renamed, so a crash never leaves half a file.
 */
class FileAppStore(private val dir: File, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun list(store: String): List<Item> {
        val file = fileOf(store)
        if (!file.isFile) return emptyList()
        val json = runCatching { Json.parseToJsonElement(file.readText()) as? JsonArray }.getOrNull() ?: return emptyList()
        return json.mapNotNull { (it as? JsonObject)?.let(Item::fromJson) }
    }

    fun add(store: String, fields: Map<String, JsonElement>): Item {
        val item = Item(newId(), fields - Item.ID)
        replaceAll(store, list(store) + item)
        return item
    }

    /** Merges [fields] into the item; returns null when there is no such item. */
    fun update(store: String, id: String, fields: Map<String, JsonElement>): Item? {
        val items = list(store)
        val old = items.firstOrNull { it.id == id } ?: return null
        val new = old.copy(fields = old.fields + (fields - Item.ID))
        replaceAll(store, items.map { if (it.id == id) new else it })
        return new
    }

    fun delete(store: String, id: String): Boolean {
        val items = list(store)
        if (items.none { it.id == id }) return false
        replaceAll(store, items.filter { it.id != id })
        return true
    }

    fun replaceAll(store: String, items: List<Item>) {
        val file = fileOf(store)
        dir.mkdirs()
        val temp = File(dir, file.name + TEMP_SUFFIX)
        temp.writeText(JsonArray(items.map(Item::toJson)).toString())
        if (!temp.renameTo(file)) {
            // Windows (unit tests) cannot rename over an existing file; Android can.
            file.delete()
            check(temp.renameTo(file)) { "could not save $store" }
        }
    }

    private fun fileOf(store: String): File {
        require(store.matches(STORE_NAME)) { "bad store name" }
        return File(dir, store + EXTENSION)
    }

    private companion object {
        val STORE_NAME = Regex("[a-z0-9-]+")
        const val EXTENSION = ".json"
        const val TEMP_SUFFIX = ".tmp"
    }
}
