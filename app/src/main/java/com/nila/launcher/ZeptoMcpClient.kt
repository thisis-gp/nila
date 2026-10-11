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
 * Headers carried on every MCP request after the session/version are known. Streamable HTTP requires
 * the negotiated protocol version on every request after initialize, not just the session id.
 * https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#protocol-version-header
 */
internal fun rpcHeaders(sessionId: String?, protocolVersion: String?): Map<String, String> = buildMap {
    sessionId?.let { put("Mcp-Session-Id", it) }
    protocolVersion?.let { put("MCP-Protocol-Version", it) }
}

/**
 * JSON-RPC client for Zepto's MCP (Streamable HTTP transport). Methods here are typed against
 * the real schemas and real responses observed on 2026-10-09 (see zepto_tools.json dump), not
 * guessed. Only create_order (Cash on Delivery) is wired for real order placement — the online
 * payment, wallet and UPI reserve-pay tools move money through a gateway and stay unwired. COD
 * is still a Commit-tier action: callers must show previewCodOrder's result and get an explicit
 * tap before calling placeCodOrder.
 */
class ZeptoMcpClient(private val auth: ZeptoAuth) {
    private val nextId = AtomicInteger(1)
    private var initialized = false
    private var sessionId: String? = null
    // The version initialize() actually negotiated. Streamable HTTP requires every request after
    // initialization to carry it, so servers that key behavior off this header don't silently fall
    // back to 2025-03-26. https://modelcontextprotocol.io/specification/2025-06-18/basic/transports#protocol-version-header
    private var protocolVersion: String? = null

    private fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject {
        val token = auth.accessToken
            ?: error("Zepto is not connected. Call ZeptoAuth.connect first.")
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", params)
        val result = httpPostRaw(ZeptoConfig.MCP_URL, request.toString(), "application/json", token, rpcHeaders(sessionId, protocolVersion))
        result.headers.entries.firstOrNull { it.key.equals("Mcp-Session-Id", ignoreCase = true) }
            ?.let { sessionId = it.value }
        val response = JSONObject(sseDataOrRaw(result.body))
        response.optJSONObject("error")?.let { error("Zepto MCP error: ${it.optString("message")}") }
        return response.getJSONObject("result")
    }

    private fun ensureInitialized() {
        if (initialized) return
        val result = rpc(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2025-06-18")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "nila-launcher").put("version", "0.1.0")),
        )
        protocolVersion = result.optString("protocolVersion").takeIf { it.isNotBlank() }
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

    /** add_saved_address arguments read from [source], shaped by the input schema Zepto publishes, so no field is guessed. */
    fun addressArgumentsFrom(source: JSONObject): JSONObject? {
        val tools = listToolsRaw()
        val schema = (0 until tools.length()).map { tools.getJSONObject(it) }
            .firstOrNull { it.optString("name") == "add_saved_address" }?.optJSONObject("inputSchema") ?: return null
        return addressArguments(schema, source)
    }

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

    /** Text list of what the account can actually pay with. Zepto's own tools require calling this before any order tool. */
    fun getPaymentMethods(): String = resultText(callTool("get_payment_methods", JSONObject()))

    /** confirmOrder=false: a real preview (items, fees, final total) with no order placed yet. Show this before placeCodOrder. */
    fun previewCodOrder(addressId: String, riderTip: Double = 0.0): String =
        resultText(callTool("create_order", codOrderArgs(addressId, riderTip, confirm = false)))

    /** Places a real Cash on Delivery order. Only call after the user has seen previewCodOrder's result and tapped confirm. */
    fun placeCodOrder(addressId: String, riderTip: Double = 0.0): String =
        resultText(callTool("create_order", codOrderArgs(addressId, riderTip, confirm = true)))

    private fun codOrderArgs(addressId: String, riderTip: Double, confirm: Boolean) = JSONObject()
        .put("userAddressId", addressId)
        .put("riderTip", riderTip)
        .put("confirmOrder", confirm)
}

// The same address field under the names different apps give it.
private val addressAliases = listOf(
    listOf("latitude", "lat"),
    listOf("longitude", "lng", "lon"),
    listOf("addressLine", "address", "fullAddress", "formattedAddress", "displayAddress"),
    listOf("label", "annotation", "addressTag", "tag"),
    listOf("pincode", "pinCode", "pin", "postalCode", "zipCode"),
    listOf("houseNumber", "flatNo", "flatNumber", "flat_no", "door"),
    listOf("landmark"),
    listOf("city"),
    listOf("state"),
    listOf("area", "locality"),
)

/**
 * Fills each property of [schema] (a JSON Schema object) from [source], by its own name or a known alias.
 * Null when a required property has no value, so nothing half-filled is saved.
 */
internal fun addressArguments(schema: JSONObject, source: JSONObject): JSONObject? {
    val properties = schema.optJSONObject("properties") ?: return null
    val required = schema.optJSONArray("required")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty()
    val arguments = JSONObject()
    properties.keys().forEach { name ->
        val names = addressAliases.firstOrNull { group -> group.any { it.equals(name, ignoreCase = true) } } ?: listOf(name)
        val value = source.pick(name, *names.toTypedArray()) ?: return@forEach
        when (properties.optJSONObject(name)?.optString("type").orEmpty()) {
            "number" -> value.toDoubleOrNull()?.let { arguments.put(name, it) }
            "integer" -> value.toLongOrNull()?.let { arguments.put(name, it) }
            "string", "" -> arguments.put(name, value)
        }
    }
    return arguments.takeIf { required.all(it::has) }
}
