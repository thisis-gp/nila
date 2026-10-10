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
 * MCP JSON-RPC 2.0 client for Swiggy's Food server (streamable HTTP, one POST
 * URL). Tool names and argument schemas come from the live `tools/list`
 * (get_addresses, search_restaurants, get_restaurant_menu, update_food_cart,
 * get_food_cart, get_payment_options, place_food_order, ...). Blocking: call
 * from Dispatchers.IO.
 */
internal class SwiggyClient(private val accessToken: String) {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val nextId = AtomicInteger(1)
    private var sessionId: String? = null
    private var initialized = false

    /** Last failure reason, shown in the UI — this vivo ROM drops app logcat output. */
    var lastError: String? = null
        private set

    /**
     * Calls one MCP tool and returns its payload: the JSON inside the first
     * text content block, or `structuredContent`. Null on failure, with the
     * reason in [lastError]. A tool-level error (`isError`) is also a failure.
     */
    fun callTool(name: String, arguments: JSONObject = JSONObject()): JSONObject? {
        if (!initialized) {
            val init = JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "NILA").put("version", "0.1"))
            rpc("initialize", init) ?: return null
            initialized = true
        }
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
                else -> null
            }
        }.getOrNull()
        return fromText ?: result.optJSONObject("structuredContent")?.takeIf { it.length() > 0 }
            ?: JSONObject().put("text", text)
    }

    private fun rpc(method: String, params: JSONObject): JSONObject? = runCatching {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json, text/event-stream")
            .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                lastError = "$method -> HTTP ${resp.code}: ${raw.take(200)}"
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
                lastError = "$method -> ${it.optString("message")}"
                return@runCatching null
            }
            parsed.optJSONObject("result")
        }
    }.onFailure { lastError = "$method -> ${it.javaClass.simpleName}: ${it.message}" }.getOrNull()

    private companion object {
        const val ENDPOINT = "https://mcp.swiggy.com/food"
    }
}
