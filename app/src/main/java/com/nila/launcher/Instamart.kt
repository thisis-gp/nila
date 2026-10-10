package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One pack size of an Instamart product, with the IDs its cart needs. */
data class InstamartItem(
    val name: String,
    val pack: String,
    val pricePaise: Long,
    val mrpPaise: Long,
    val imageUrl: String?,
    val spinId: String,
    val skuId: String?,
)

/**
 * Swiggy Instamart through Swiggy's own MCP server (`/im`), signed in with the same Swiggy session as food.
 * Delivers to the address the user last chose in NILA, so groceries and food go to the same place.
 * Adding items only updates the Instamart cart; nothing is ordered here.
 */
internal class InstamartShop(private val context: Context) {
    private var client: McpClient? = null
    private val cart = linkedMapOf<String, Pair<InstamartItem, Int>>()
    private val memory = NilaMemory(context)

    val connected: Boolean get() = SwiggyAuth.currentSession(context) != null

    private suspend fun call(tool: String, args: JSONObject = JSONObject()): JSONObject? = withContext(Dispatchers.IO) {
        val session = SwiggyAuth.currentSession(context) ?: return@withContext null
        val c = client ?: McpClient(ENDPOINT, session.accessToken).also { client = it }
        c.callTool(tool, args)
    }

    /** The address NILA delivers food to; Instamart shares the Swiggy account's saved addresses. */
    private suspend fun addressId(): String? =
        context.getSharedPreferences("nila_food", Context.MODE_PRIVATE).getString("last_address", null)
            ?: call("get_addresses")?.let { allObjects(it) }?.firstNotNullOfOrNull { it.pick("id", "addressId") }

    /** Items named in a Preference fact come first, then pack sizes matching what the user asked for, then the cheapest. */
    suspend fun search(query: String, desiredSize: String?): List<InstamartItem> {
        val address = addressId() ?: return emptyList()
        val result = call("search_products", JSONObject().put("query", query).put("addressId", address)) ?: return emptyList()
        val wanted = desiredSize?.let(::normalizedSize)
        val facts = memory.facts()
        return parseInstamart(result)
            .sortedWith(
                compareByDescending<InstamartItem> { sharesPreferenceWord(it.name, facts) }.thenByDescending { item ->
                    wanted != null && normalizedSize(item.pack)?.let { (unit, value) -> unit == wanted.first && kotlin.math.abs(value - wanted.second) < 1.0 } == true
                }.thenBy { it.pricePaise },
            )
            .take(RESULTS)
    }

    /** The Instamart column of a grocery comparison, with steppers that change the real Instamart cart. */
    fun group(query: String, items: List<InstamartItem>, scope: CoroutineScope, onProblem: (String) -> Unit): ProductProviderGroup {
        if (!connected) return ProductProviderGroup("Swiggy Instamart", "not connected", emptyList(), "Connect Swiggy in Settings to compare Instamart.")
        val products = items.map { item ->
            PickableProduct(
                title = item.name,
                detail = listOf(item.pack, item.mrpPaise.takeIf { it > item.pricePaise }?.let { "MRP ${rupeesText(it)}" }).filterNotNull().joinToString(" · "),
                price = rupeesText(item.pricePaise),
                imageUrl = item.imageUrl,
                initialQuantity = cart[item.spinId]?.second ?: 0,
            ) { quantity ->
                scope.launch {
                    if (!setQuantity(item, quantity)) onProblem("Instamart couldn't update the cart right now.")
                }
            }
        }
        val summary = items.minOfOrNull { it.pricePaise }?.let { "from ${rupeesText(it)}" } ?: "no results"
        return ProductProviderGroup("Swiggy Instamart", summary, products, if (products.isEmpty()) "No Instamart results for \"$query\"." else null)
    }

    /** Instamart's update_cart replaces the whole cart, so the full list is sent each time; 0 removes the item. */
    private suspend fun setQuantity(item: InstamartItem, quantity: Int): Boolean {
        val address = addressId() ?: return false
        if (quantity <= 0) cart.remove(item.spinId) else cart[item.spinId] = item to quantity
        val lines = JSONArray()
        cart.values.forEach { (line, count) ->
            lines.put(JSONObject().put("spinId", line.spinId).put("quantity", count).apply { line.skuId?.let { put("skuId", it) } })
        }
        if (lines.length() == 0) return call("clear_cart") != null
        return call("update_cart", JSONObject().put("selectedAddressId", address).put("items", lines)) != null
    }

    private companion object {
        const val ENDPOINT = "https://mcp.swiggy.com/im"
        const val RESULTS = 6
    }
}

private fun rupeesText(paise: Long) = if (paise % 100 == 0L) "₹${paise / 100}" else "₹%.2f".format(paise / 100.0)

/** Each product's pack sizes become separate rows; out-of-stock packs are left out. */
private fun parseInstamart(result: JSONObject): List<InstamartItem> {
    val products = result.optJSONArray("products") ?: return emptyList()
    return (0 until products.length()).flatMap { i ->
        val product = products.optJSONObject(i) ?: return@flatMap emptyList()
        if (product.optBoolean("inStock", true).not()) return@flatMap emptyList()
        val name = listOf(product.optString("brand"), product.optString("displayName")).filter(String::isNotBlank).distinct()
            .joinToString(" ").let { full -> product.optString("displayName").ifBlank { full } }
        val variations = product.optJSONArray("variations") ?: return@flatMap emptyList()
        (0 until variations.length()).mapNotNull { j ->
            val v = variations.optJSONObject(j) ?: return@mapNotNull null
            if (!v.optBoolean("isInStockAndAvailable", true)) return@mapNotNull null
            val price = v.optJSONObject("price")
            val offer = price?.let { it.optDouble("offerPrice", it.optDouble("mrp", Double.NaN)) } ?: Double.NaN
            if (offer.isNaN()) return@mapNotNull null
            InstamartItem(
                name = v.optString("displayName").ifBlank { name },
                pack = v.optString("quantityDescription"),
                pricePaise = (offer * PAISE_PER_RUPEE).toLong(),
                mrpPaise = ((price?.optDouble("mrp", offer) ?: offer) * PAISE_PER_RUPEE).toLong(),
                imageUrl = instamartImage(v) ?: instamartImage(product),
                spinId = v.optString("spinId").ifBlank { return@mapNotNull null },
                skuId = v.optString("skuId").ifBlank { null },
            )
        }
    }
}

private const val PAISE_PER_RUPEE = 100

/** Instamart images are full URLs or media IDs on Swiggy's CDN. */
private fun instamartImage(o: JSONObject): String? {
    val raw = o.optJSONArray("imageIds")?.optString(0)?.ifBlank { null }
        ?: o.optJSONArray("images")?.optString(0)?.ifBlank { null }
        ?: o.pick("imageUrl", "image", "imageId")
        ?: return null
    return if (raw.startsWith("http")) raw else "https://media-assets.swiggy.com/swiggy/image/upload/fl_lossy,f_auto,q_auto,w_300/$raw"
}
