package com.nila.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

data class ZeptoTool(val name: String, val description: String)
data class CartItem(
    val productVariantId: String,
    val storeProductId: String,
    val name: String,
    val priceInPaisa: Int,
    val quantity: Int,
    val imageUrl: String?,
)
data class SavedAddress(val id: String, val label: String, val addressLine: String)
data class ZeptoProduct(
    val productVariantId: String,
    val storeProductId: String,
    val name: String,
    val priceInPaisa: Int,
    val packSize: String,
    val imageUrl: String?,
)

/** Zepto's MCP replies over the Streamable HTTP transport, which may send text/event-stream ("event: ...\ndata: {...}") instead of a plain JSON body. */
internal fun sseDataOrRaw(raw: String): String =
    raw.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull() ?: raw

/**
 * JSON-RPC client for Zepto's MCP (Streamable HTTP transport). Methods here are typed against
 * the real schemas and real responses observed on 2026-10-09 (see zepto_tools.json dump), not
 * guessed. Order placement (create_order / payment tools) is intentionally not wired up: that's
 * a Commit-tier action needing the same visible-confirmation flow the rest of NILA uses before
 * anything consequential, and real payments are out of scope for the hackathon build.
 */
class ZeptoMcpClient(private val auth: ZeptoAuth) {
    private val nextId = AtomicInteger(1)
    private var initialized = false
    private var sessionId: String? = null

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val token = auth.accessToken
            ?: error("Zepto is not connected. Call ZeptoAuth.connect first.")
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val extraHeaders = sessionId?.let { mapOf("Mcp-Session-Id" to it) } ?: emptyMap()
        val result = httpPostRaw(ZeptoConfig.MCP_URL, request.toString(), "application/json", token, extraHeaders)
        result.headers.entries.firstOrNull { it.key.equals("Mcp-Session-Id", ignoreCase = true) }
            ?.let { sessionId = it.value }
        val response = JSONObject(sseDataOrRaw(result.body))
        response.optJSONObject("error")?.let { error("Zepto MCP error: ${it.optString("message")}") }
        return response.getJSONObject("result")
    }

    private fun ensureInitialized() {
        if (initialized) return
        rpc(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "nila-launcher").put("version", "0.1.0")),
        )
        initialized = true
    }

    /** Raw tools/list, including each tool's input schema. Used to design argument shapes instead of guessing them. */
    fun listToolsRaw(): JSONArray {
        ensureInitialized()
        return rpc("tools/list").getJSONArray("tools")
    }

    fun listTools(): List<ZeptoTool> {
        val tools = listToolsRaw()
        return (0 until tools.length()).map { index ->
            val tool = tools.getJSONObject(index)
            ZeptoTool(tool.getString("name"), tool.optString("description"))
        }
    }

    fun callTool(name: String, arguments: JSONObject): JSONObject {
        ensureInitialized()
        return rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
    }

    private fun resultText(result: JSONObject): String =
        result.optJSONArray("content")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() } ?: result.toString()

    /** Confirmed 2026-10-09 against the real account: structuredContent.addresses[], even though no outputSchema is declared for this tool. */
    fun listSavedAddresses(): List<SavedAddress> {
        val result = callTool("list_saved_addresses", JSONObject())
        val addresses = result.optJSONObject("structuredContent")?.optJSONArray("addresses") ?: JSONArray()
        return (0 until addresses.length()).map { index ->
            val address = addresses.getJSONObject(index)
            SavedAddress(address.getString("id"), address.optString("label"), address.optString("addressLine"))
        }
    }

    /** Also sets the delivery store context for the rest of this session, per Zepto's tool description. */
    fun selectSavedAddress(addressId: String): String =
        resultText(callTool("select_saved_address", JSONObject().put("addressId", addressId)))

    fun addSavedAddress(arguments: JSONObject): String =
        resultText(callTool("add_saved_address", arguments))

    fun getUserDetails(): JSONObject =
        callTool("get_user_details", JSONObject()).optJSONObject("structuredContent") ?: JSONObject()

    /** Confirmed 2026-10-09: tool is "search_products", declared outputSchema is structuredContent.products[]. */
    fun searchProducts(query: String): List<ZeptoProduct> {
        val result = callTool("search_products", JSONObject().put("query", query))
        val products = result.optJSONObject("structuredContent")?.optJSONArray("products") ?: JSONArray()
        return (0 until products.length()).mapNotNull { index ->
            val product = products.getJSONObject(index)
            val variantId = product.optString("productVariantId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val storeProductId = product.optString("storeProductId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ZeptoProduct(
                productVariantId = variantId,
                storeProductId = storeProductId,
                name = product.optString("name", "Zepto item"),
                priceInPaisa = product.optInt("price", 0),
                packSize = product.optString("packSize", ""),
                imageUrl = product.optString("imageUrl").takeIf { it.isNotBlank() },
            )
        }
    }

    /** Prepare-tier only: sets a line's quantity in the real Zepto cart. 0 removes it. Never places or pays for an order. */
    fun setCartQuantity(deviceId: String, productVariantId: String, storeProductId: String, name: String, quantity: Int): String {
        val item = JSONObject()
            .put("productVariantId", productVariantId)
            .put("storeProductId", storeProductId)
            .put("quantity", quantity)
            .put("name", name)
        val arguments = JSONObject().put("deviceId", deviceId).put("cartItems", JSONArray().put(item))
        return resultText(callTool("update_cart", arguments))
    }

    fun addToCart(deviceId: String, product: ZeptoProduct, quantity: Int = 1): String =
        setCartQuantity(deviceId, product.productVariantId, product.storeProductId, product.name, quantity)

    /** Human-readable cart summary. No outputSchema declared for this tool, so the content text is the reliable part. */
    fun viewCart(): String = resultText(callTool("view_cart", JSONObject()))

    /** Confirmed 2026-10-10 against the real account: structuredContent.items[], same as the text summary but structured for editing and a price total. */
    fun viewCartItems(): List<CartItem> {
        val result = callTool("view_cart", JSONObject())
        val items = result.optJSONObject("structuredContent")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.getJSONObject(index)
            val variantId = item.optString("productVariantId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val storeProductId = item.optString("storeProductId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CartItem(
                productVariantId = variantId,
                storeProductId = storeProductId,
                name = item.optString("name", "Item"),
                priceInPaisa = item.optInt("price", 0),
                quantity = item.optInt("quantity", 1),
                imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() },
            )
        }
    }
}
