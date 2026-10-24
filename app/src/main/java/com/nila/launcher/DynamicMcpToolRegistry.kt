package com.nila.launcher

import org.json.JSONArray
import org.json.JSONObject

internal data class DynamicMcpTool(
    val serverId: String,
    val serverName: String,
    val name: String,
    val capability: CapabilityDescriptor,
)

internal object DynamicMcpToolRegistry {
    private const val MAX_TOOLS_PER_SERVER = 64
    private const val MAX_PARAMETERS = 16
    private val safeName = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")

    fun parse(serverId: String, serverName: String, tools: JSONArray): List<DynamicMcpTool> = buildList {
        val server = identifier(serverId)
        for (index in 0 until minOf(tools.length(), MAX_TOOLS_PER_SERVER)) {
            val raw = tools.optJSONObject(index) ?: continue
            val name = raw.optString("name").takeIf(safeName::matches) ?: continue
            val schema = raw.optJSONObject("inputSchema") ?: continue
            if (schema.optString("type") != "object") continue
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            if (properties.length() > MAX_PARAMETERS) continue
            val required = schema.optJSONArray("required") ?: JSONArray()
            val requiredNames = (0 until required.length()).map { required.optString(it) }.toSet()
            if (requiredNames.any { !properties.has(it) }) continue
            val params = mutableListOf<Param>()
            var supported = true
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!safeName.matches(key)) {
                    supported = false
                    break
                }
                val property = properties.optJSONObject(key) ?: run { supported = false; break }
                val type = when (property.optString("type")) {
                    "string" -> ParamType.STRING
                    "integer" -> ParamType.INT
                    "number" -> ParamType.NUMBER
                    "boolean" -> ParamType.BOOLEAN
                    else -> null
                } ?: run { supported = false; break }
                params += Param(key, type, required = key in requiredNames, label = property.optString("description").take(80).ifBlank { key })
            }
            if (!supported) continue
            val id = "mcp_${server}_${identifier(name)}"
            val description = "${serverName.take(40)} · ${raw.optString("description").take(320)}"
            add(
                DynamicMcpTool(
                    serverId = server,
                    serverName = serverName.take(40),
                    name = name,
                    capability = CapabilityDescriptor(
                        id = id,
                        description = description,
                        params = params,
                        // Until a server tool has a reviewed permission mapping, every dynamic call is explicit.
                        permission = PermissionClass.COMMIT,
                        label = "$serverName: $name",
                    ),
                ),
            )
        }
    }.distinctBy { it.capability.id }

    private fun identifier(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "tool" }
}
