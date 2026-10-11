package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A recipe with a storage data source at /items unless the test says otherwise. [extra] must end with a comma. */
fun recipe(
    components: String,
    data: String = """{"items":{"provider":"storage","store":"s"}}""",
    permissions: String = """["storage.local"]""",
    extra: String = "",
): JsonObject = Json.parseToJsonElement(
    """{"recipe":"nila.app/1","id":"test-app","title":"Test","permissions":$permissions,"data":$data,$extra"components":$components}""",
).jsonObject

fun JsonObject.components(): List<JsonObject> = (getValue("components") as JsonArray).map { it as JsonObject }

fun JsonObject.permissionList(): List<String?> = (getValue("permissions") as JsonArray).map { it.stringOrNull() }
