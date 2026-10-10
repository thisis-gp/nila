package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

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

/** The live Instamart cart from get_cart: Swiggy's own bill, never a total NILA adds up itself. */
data class InstamartCart(
    val lines: List<Pair<InstamartItem, Int>>,
    val bill: List<Pair<String, String>>,
    val toPay: String?,
    val addressId: String?,
    val addressLabel: String?,
    /** Swiggy's warning (address not serviceable, under the minimum order, cart expired), shown as is. */
    val warning: String?,
    val paymentMethods: List<String>,
) {
    /** Cash on delivery when Swiggy offers it: online payment would hand off to a UPI app, which this card does not do. */
    /** The same order to place: lines and quantities, the amount to pay and the address all unchanged. */
    fun sameOrderAs(other: InstamartCart) =
        lines.associate { it.first.spinId to it.second } == other.lines.associate { it.first.spinId to it.second } &&
            toPay == other.toPay && addressId == other.addressId

    val cashMethod: String? get() = paymentMethods.firstOrNull { Regex("""(?i)\b(cash|cod)\b""").containsMatchIn(it) }
}

/**
 * Swiggy Instamart through Swiggy's own MCP server (`/im`), signed in with the same Swiggy session as food.
 * Delivers to the address the user last chose in NILA, so groceries and food go to the same place.
 * Adding items only updates the Instamart cart; nothing is ordered here.
 */
internal class InstamartShop(private val context: Context) {
    private var client: McpClient? = null
    private val cart = linkedMapOf<String, Pair<InstamartItem, Int>>()
    // update_cart replaces the whole cart, so changes run one at a time, in the order they were tapped.
    private val cartLock = Mutex()
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

    /** Reads the real cart, and syncs the steppers' copy with it, so a change made in the Swiggy app is not undone. */
    suspend fun cart(): InstamartCart? {
        if (!connected) return null
        val result = call("get_cart") ?: return null
        val parsed = parseInstamartCart(result, addressId())
        cartLock.withLock {
            cart.clear()
            parsed.lines.forEach { (item, quantity) -> cart[item.spinId] = item to quantity }
        }
        return parsed
    }

    /** The address chosen in NILA, with its full line, so Zepto can deliver to the same place. Null without Swiggy. */
    suspend fun nilaAddress(): FoodAddress? {
        if (!connected) return null
        val id = addressId() ?: return null
        return call("get_addresses")?.let(::parseAddresses)?.firstOrNull { it.id == id }
    }

    suspend fun setLine(item: InstamartItem, quantity: Int): Boolean = setQuantity(item, quantity)

    /** Places a real Instamart order. Only call after the user confirmed the cart, address and payment method. */
    suspend fun checkout(addressId: String, paymentMethod: String): String {
        val result = call("checkout", JSONObject().put("addressId", addressId).put("paymentMethod", paymentMethod))
            ?: error(client?.lastError ?: "Instamart did not answer")
        val order = allObjects(result).firstOrNull { it.has("orderId") || it.has("orders") } ?: result
        return listOfNotNull(
            order.pick("orderId")?.let { "Order $it" },
            order.pick("status"),
            order.pick("paymentMethod")?.let { "Pay by $it" },
            order.pick("orderCount")?.let { "$it orders" },
        ).joinToString(" · ").ifBlank { "Instamart accepted the order." }
    }

    /** Instamart's update_cart replaces the whole cart, so the full list is sent each time; 0 removes the item. */
    private suspend fun setQuantity(item: InstamartItem, quantity: Int): Boolean = cartLock.withLock {
        val address = addressId() ?: return false
        if (quantity <= 0) cart.remove(item.spinId) else cart[item.spinId] = item to quantity
        val lines = JSONArray()
        cart.values.forEach { (line, count) ->
            lines.put(JSONObject().put("spinId", line.spinId).put("quantity", count).apply { line.skuId?.let { put("skuId", it) } })
        }
        if (lines.length() == 0) return call("clear_cart") != null
        call("update_cart", JSONObject().put("selectedAddressId", address).put("items", lines)) != null
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
                pricePaise = (offer * PAISE_PER_RUPEE).roundToLong(),
                mrpPaise = ((price?.optDouble("mrp", offer) ?: offer) * PAISE_PER_RUPEE).roundToLong(),
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

/** get_cart's payload as an [InstamartCart]. [fallbackAddressId] is used when Swiggy names no selected address. */
internal fun parseInstamartCart(result: JSONObject, fallbackAddressId: String?): InstamartCart {
    val data = allObjects(result).firstOrNull { it.has("billBreakdown") || it.has("cartTotalAmount") || it.has("cartAbsent") }
        ?: return InstamartCart(emptyList(), emptyList(), null, fallbackAddressId, null, null, emptyList())
    val items = data.optJSONArray("items")
    val lines = (0 until (items?.length() ?: 0)).mapNotNull { i ->
        val o = items!!.optJSONObject(i) ?: return@mapNotNull null
        val price = o.optDouble("discountedFinalPrice", o.optDouble("mrp", 0.0))
        InstamartItem(
            name = o.optString("itemName", "Item"),
            pack = o.optString("itemVariant"),
            pricePaise = (price * PAISE_PER_RUPEE).roundToLong(),
            mrpPaise = (o.optDouble("mrp", price) * PAISE_PER_RUPEE).roundToLong(),
            imageUrl = instamartImage(o),
            spinId = o.optString("spinId").ifBlank { return@mapNotNull null },
            skuId = o.optString("skuId").ifBlank { null },
        ) to o.optInt("quantity", 1)
    }
    val breakdown = data.optJSONObject("billBreakdown")
    val billItems = breakdown?.optJSONArray("lineItems")
    val address = data.optJSONObject("selectedAddressDetails")
    val methods = data.optJSONArray("availablePaymentMethods")
    return InstamartCart(
        lines = lines,
        bill = (0 until (billItems?.length() ?: 0)).mapNotNull { i ->
            billItems!!.optJSONObject(i)?.let { line -> line.optString("label") to line.optString("value") }
        },
        toPay = breakdown?.optJSONObject("toPay")?.pick("value") ?: data.pick("cartTotalAmount"),
        addressId = address?.pick("id") ?: fallbackAddressId,
        addressLabel = address?.let { it.pick("annotation", "name") ?: it.pick("address") } ?: data.pick("selectedAddress"),
        warning = data.pick("addressWarning") ?: data.optJSONObject("cartWarning")?.pick("message") ?: data.pick("cartAbsentReason"),
        paymentMethods = (0 until (methods?.length() ?: 0)).map { methods!!.optString(it) },
    )
}
