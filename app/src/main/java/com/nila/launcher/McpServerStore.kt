package com.nila.launcher

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

internal data class McpServer(
    val id: String,
    val name: String,
    val endpoint: String,
    val bearerToken: String,
)

internal object McpEndpoint {
    fun validate(value: String): String? {
        if (value.length !in 1..2048) return "Enter an HTTPS endpoint up to 2048 characters."
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return "Enter a valid HTTPS endpoint."
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) {
            return "Use an HTTPS URL with a host, without embedded credentials or a fragment."
        }
        return null
    }
}

/** Manual endpoints and tokens stay encrypted on this phone and are never included in model tool schemas. */
internal class McpServerStore(context: Context) {
    private val app = context.applicationContext
    private val prefs by lazy {
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            app,
            PREFS,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun all(): List<McpServer> = runCatching {
        readAll()
    }.getOrDefault(emptyList())

    private fun readAll(): List<McpServer> {
        val array = JSONArray(prefs.getString(SERVERS, "[]"))
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            McpServer(obj.getString("id"), obj.getString("name"), obj.getString("endpoint"), obj.optString("token"))
        }
    }

    fun add(nameInput: String, endpointInput: String, tokenInput: String): Result<McpServer> = runCatching {
        val name = nameInput.trim()
        require(name.length in 1..40) { "Name must be 1–40 characters." }
        require(name.matches(Regex("^[A-Za-z0-9][A-Za-z0-9 ._-]{0,39}$"))) { "Name can use letters, numbers, spaces, dots, underscores and hyphens." }
        require(McpEndpoint.validate(endpointInput) == null) { McpEndpoint.validate(endpointInput).orEmpty() }
        require(tokenInput.length <= MAX_TOKEN) { "Bearer token is too long." }
        val current = readAll()
        require(current.size < MAX_SERVERS) { "You can add up to $MAX_SERVERS MCP servers." }
        val server = McpServer("custom_${UUID.randomUUID()}", name, endpointInput.trim(), tokenInput)
        val json = JSONArray().apply { (current + server).forEach { put(encode(it)) } }
        check(prefs.edit().putString(SERVERS, json.toString()).commit()) { "Couldn't save this server securely." }
        server
    }

    fun remove(id: String): Boolean {
        return runCatching {
            val next = readAll().filterNot { it.id == id }
            prefs.edit().putString(SERVERS, JSONArray().apply { next.forEach { put(encode(it)) } }.toString()).commit()
        }.getOrDefault(false)
    }

    private fun encode(server: McpServer) = JSONObject()
        .put("id", server.id).put("name", server.name).put("endpoint", server.endpoint).put("token", server.bearerToken)

    private companion object {
        const val PREFS = "nila_mcp_servers_secure"
        const val SERVERS = "servers"
        const val MAX_SERVERS = 8
        const val MAX_TOKEN = 4096
    }
}
