package com.nila.launcher

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP JSON-RPC 2.0 client for a food server at [endpoint] (Swiggy's Food server first (streamable HTTP, one POST
 * URL). Tool names and argument schemas come from the live `tools/list`
 * (get_addresses, search_restaurants, get_restaurant_menu, update_food_cart,
 * get_food_cart, get_payment_options, place_food_order, ...). Blocking: call
 * from Dispatchers.IO.
 */
internal class McpClient(private val endpoint: String, private val accessToken: String?) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val nextId = AtomicInteger(1)
    private var sessionId: String? = null
    private var initialized = false

    init {
        require(McpEndpoint.validate(endpoint) == null) { "MCP endpoint must be a valid HTTPS URL without embedded credentials." }
    }

    /** Last failure reason, shown in the UI — this vivo ROM drops app logcat output. */
    var lastError: String? = null
        private set

    fun listToolsRaw(): JSONArray {
        ensureInitialized()
        return rpc("tools/list", JSONObject())?.optJSONArray("tools")
            ?: error(lastError ?: "MCP server did not return tools")
    }

    /**
     * Calls one MCP tool and returns its payload: the JSON inside the first
     * text content block, or `structuredContent`. Null on failure, with the
     * reason in [lastError]. A tool-level error (`isError`) is also a failure.
     */
    fun callTool(name: String, arguments: JSONObject = JSONObject()): JSONObject? {
        if (!ensureInitialized()) return null
        val result = rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments)) ?: return null
        val text = result.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty().trim()
        if (result.optBoolean("isError")) {
            lastError = "$name: ${text.lineSequence().firstOrNull().orEmpty()}"
            return null
        }
        val fromText = runCatching {
            when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONObject().put("items", JSONArray(text))
                // Instamart puts display instructions before its JSON; the payload starts on its own line.
                text.contains("\n{") -> JSONObject(text.substring(text.indexOf("\n{") + 1))
                else -> null
            }
        }.getOrNull()
        return fromText ?: result.optJSONObject("structuredContent")?.takeIf { it.length() > 0 }
            ?: JSONObject().put("text", text)
    }

    private fun ensureInitialized(): Boolean {
        if (initialized) return true
        val init = JSONObject()
            .put("protocolVersion", "2024-11-05")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "NILA").put("version", "0.1"))
        if (rpc("initialize", init) == null) return false
        initialized = true
        return true
    }

    private fun rpc(method: String, params: JSONObject): JSONObject? = runCatching {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .apply { accessToken?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") } }
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body
            if (body != null && body.contentLength() > MAX_RESPONSE_BYTES) {
                lastError = "$method -> response too large"
                return@runCatching null
            }
            val raw = body?.source()?.readByteArray(MAX_RESPONSE_BYTES + 1)?.toString(Charsets.UTF_8).orEmpty()
            if (raw.length > MAX_RESPONSE_BYTES) {
                lastError = "$method -> response too large"
                return@runCatching null
            }
            if (!resp.isSuccessful) {
                lastError = "$method -> HTTP ${resp.code}"
                return@runCatching null
            }
            resp.header("Mcp-Session-Id")?.let { sessionId = it }
            val json = if (resp.header("Content-Type").orEmpty().contains("text/event-stream")) {
                // Streamable HTTP may reply as SSE: the JSON-RPC message is the last `data:` line.
                raw.lineSequence().lastOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
            } else {
                raw
            }
            if (json.isNullOrBlank()) {
                lastError = "$method -> empty body"
                return@runCatching null
            }
            val parsed = JSONObject(json)
            parsed.optJSONObject("error")?.let {
                lastError = "$method -> server returned an RPC error"
                return@runCatching null
            }
            parsed.optJSONObject("result")
        }
    }.onFailure { lastError = "$method -> ${it.javaClass.simpleName}" }.getOrNull()

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576L
    }
}
