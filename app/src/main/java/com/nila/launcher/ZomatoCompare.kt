package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** What the payment card says about Zomato: a priced quote, or why there is none. */
sealed interface ZomatoComparison

/** One Swiggy cart line found on Zomato, with the variant Zomato's cart needs. */
data class ZomatoLine(val name: String, val variantId: String, val quantity: Int, val price: Int)

/**
 * The same order on Zomato. [itemsTotal] compares dish prices only (both apps add their own fees);
 * [total] is Zomato's real cart total, filled in only after the user taps "Check Zomato total".
 */
data class ZomatoQuote(
    val restaurantName: String,
    val restaurantId: Long,
    val addressId: String,
    val addressName: String,
    val eta: String,
    val lines: List<ZomatoLine>,
    val missing: List<String>,
    val itemsTotal: Int,
    val total: Int? = null,
) : ZomatoComparison

/** Why there is no comparison, said plainly on the card instead of guessing a match. */
data class ZomatoGap(val reason: String) : ZomatoComparison

/** One dish on a Zomato menu, its price in rupees. */
data class ZomatoDish(val name: String, val price: Int)

/** The same shop on Zomato: its dishes for what the user asked, cheapest first, or why there are none ([gap]). */
data class ZomatoShop(val name: String, val dishes: List<ZomatoDish>, val gap: String? = null)

/**
 * Prices the user's Swiggy cart on Zomato through Zomato's MCP server. Every step is read-only except
 * [total], which creates a Zomato cart (never checks out) and runs only from the user's tap.
 */
internal class ZomatoCompare(private val context: Context) {
    private var client: McpClient? = null
    // Per address and per shop, so scrolling back to a card never asks Zomato again.
    private val addressIds = ConcurrentHashMap<String, String>()
    private val shops = ConcurrentHashMap<String, ZomatoShop>()
    private val addressLock = Mutex()

    val connected: Boolean get() = ZomatoAuth.currentSession(context) != null

    private suspend fun call(tool: String, args: JSONObject = JSONObject()): JSONObject? = withContext(Dispatchers.IO) {
        val session = ZomatoAuth.currentSession(context) ?: return@withContext null
        val c = client ?: McpClient(ENDPOINT, session.accessToken).also { client = it }
        c.callTool(tool, args)
    }

    suspend fun quote(address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView): ZomatoComparison {
        val addresses = call("get_saved_addresses_for_user")?.optJSONArray("addresses") ?: return ZomatoGap("Zomato didn't answer")
        val match = closestAddress(address, addresses) ?: return ZomatoGap("No Zomato address near ${address.label}")
        val addressId = match.pick("address_id") ?: return ZomatoGap("No Zomato address near ${address.label}")

        val found = call("get_restaurants_for_keyword", JSONObject().put("address_id", addressId).put("keyword", restaurant.name).put("page_size", SEARCH_SIZE))
            ?.let { allObjects(it) }.orEmpty()
            .firstOrNull { it.has("res_id") && sameRestaurant(restaurant.name, it.pick("name").orEmpty()) }
            ?: return ZomatoGap("${restaurant.name} isn't on Zomato near ${address.label}")
        val resId = found.pick("res_id")?.toLongOrNull() ?: return ZomatoGap("${restaurant.name} isn't on Zomato near ${address.label}")

        val listing = call("get_menu_items_listing", JSONObject().put("res_id", resId).put("address_id", addressId))
            ?.let { allObjects(it) }.orEmpty().filter { it.has("item_name") && it.has("variant_id") }
        val wanted = cart.lines.associateWith { line -> listing.firstOrNull { sameDish(line.name, it.pick("item_name").orEmpty()) } }
        val categories = wanted.values.filterNotNull().mapNotNull { it.optJSONArray("categories")?.optString(0) }.distinct()
        if (categories.isEmpty()) return ZomatoGap("${found.pick("name")} on Zomato doesn't list these dishes")

        val menu = call(
            "get_restaurant_menu_by_categories",
            JSONObject().put("res_id", resId).put("address_id", addressId).put("categories", JSONArray(categories)),
        )?.let { allObjects(it) }.orEmpty().filter { it.has("variant_id") && it.has("price") }
        val lines = mutableListOf<ZomatoLine>()
        val missing = mutableListOf<String>()
        wanted.forEach { (line, mapped) ->
            val item = mapped?.let { m -> menu.firstOrNull { it.pick("variant_id") == m.pick("variant_id") } }
            val price = item?.pick("discounted_price", "price")?.toDoubleOrNull()?.toInt()
            if (item == null || price == null) missing += line.name
            else lines += ZomatoLine(item.pick("name").orEmpty(), item.pick("variant_id")!!, line.quantity, price)
        }
        if (lines.isEmpty()) return ZomatoGap("${found.pick("name")} on Zomato doesn't list these dishes")
        return ZomatoQuote(
            restaurantName = found.pick("name").orEmpty(),
            restaurantId = resId,
            addressId = addressId,
            addressName = match.pick("location_name").orEmpty(),
            eta = found.pick("eta").orEmpty(),
            lines = lines,
            missing = missing,
            itemsTotal = lines.sumOf { it.price * it.quantity },
        )
    }

    private suspend fun addressIdFor(address: FoodAddress): String? = addressLock.withLock {
        addressIds[address.id]?.let { return it }
        val saved = call("get_saved_addresses_for_user")?.optJSONArray("addresses") ?: return null
        closestAddress(address, saved)?.pick("address_id")?.also { addressIds[address.id] = it }
    }

    /**
     * The shop the user is looking at on Swiggy, found on Zomato, with every dish on its menu that matches
     * [query] ("biryani" finds Chicken, Mutton and Egg Biryani) and its Zomato price. Read-only.
     */
    suspend fun shop(address: FoodAddress, restaurantName: String, query: String): ZomatoShop {
        val key = "${address.id}|$restaurantName|$query"
        shops[key]?.let { return it }
        fun gap(reason: String) = ZomatoShop(restaurantName, emptyList(), reason)
        val addressId = addressIdFor(address) ?: return gap("No Zomato address near ${address.label}")
        val found = call("get_restaurants_for_keyword", JSONObject().put("address_id", addressId).put("keyword", restaurantName).put("page_size", SEARCH_SIZE))
            ?.let { allObjects(it) }.orEmpty()
            .firstOrNull { it.has("res_id") && sameRestaurant(restaurantName, it.pick("name").orEmpty()) }
            ?: return gap("Not on Zomato near ${address.label}")
        val resId = found.pick("res_id")?.toLongOrNull() ?: return gap("Not on Zomato near ${address.label}")
        val listing = call("get_menu_items_listing", JSONObject().put("res_id", resId).put("address_id", addressId))
            ?.let { allObjects(it) }.orEmpty().filter { it.has("item_name") && it.has("variant_id") }
            .filter { mentionsDish(it.pick("item_name").orEmpty(), query) }
        val categories = listing.mapNotNull { it.optJSONArray("categories")?.optString(0) }.distinct()
        if (categories.isEmpty()) return gap("No $query on its Zomato menu")
        val wanted = listing.mapNotNull { it.pick("variant_id") }.toSet()
        val dishes = call(
            "get_restaurant_menu_by_categories",
            JSONObject().put("res_id", resId).put("address_id", addressId).put("categories", JSONArray(categories)),
        )?.let { allObjects(it) }.orEmpty()
            .filter { it.pick("variant_id") in wanted }
            .mapNotNull { item ->
                val price = item.pick("discounted_price", "price")?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                ZomatoDish(item.pick("name", "item_name").orEmpty(), price)
            }
            .distinctBy { it.name.lowercase() }
            .sortedBy { it.price }
        if (dishes.isEmpty()) return gap("Zomato didn't price its $query")
        return ZomatoShop(found.pick("name").orEmpty().ifBlank { restaurantName }, dishes).also { shops[key] = it }
    }

    /** Creates a Zomato cart for the same items to read its real total. Never checks out. */
    suspend fun total(quote: ZomatoQuote, paymentMethod: String): ZomatoQuote? {
        val items = JSONArray()
        quote.lines.forEach { items.put(JSONObject().put("variant_id", it.variantId).put("quantity", it.quantity)) }
        val result = call(
            "create_cart",
            JSONObject().put("res_id", quote.restaurantId).put("items", items).put("address_id", quote.addressId)
                .put("payment_type", if (paymentMethod.equals("Cash", ignoreCase = true)) "cash_on_delivery" else "upi"),
        ) ?: return null
        val total = findString(result, "total_amount")?.toDoubleOrNull()?.toInt() ?: return null
        return quote.copy(total = total)
    }

    private companion object {
        const val ENDPOINT = "https://mcp-server.zomato.com/mcp"
        const val SEARCH_SIZE = 5
    }
}

/** A Zomato address on the same pincode, else in the same area. Never just "any address in the city". */
internal fun closestAddress(address: FoodAddress, zomato: JSONArray): JSONObject? {
    val all = (0 until zomato.length()).mapNotNull { zomato.optJSONObject(it) }
    val pincode = Regex("""\b\d{6}\b""").find(address.line)?.value
    pincode?.let { pin -> all.firstOrNull { it.pick("location_name").orEmpty().contains(pin) }?.let { return it } }
    val areas = address.line.substringAfter(": ").split(",").map { it.trim().lowercase() }.filter { it.length > AREA_MIN && it !in GENERIC_PLACES }
    return all.firstOrNull { z -> val name = z.pick("location_name").orEmpty().lowercase(); areas.any { name.contains(it) } }
}

/** "Paradise Biryani" matches "Paradise Biryani - A Legend Since 1953"; brand words must agree. */
internal fun sameRestaurant(swiggy: String, zomato: String): Boolean {
    val a = swiggy.lowercase().substringBefore(" - ").trim()
    val b = zomato.lowercase().substringBefore(" - ").trim()
    return a.isNotBlank() && (a == b || b.startsWith(a) || a.startsWith(b))
}

/** The dish name without the size NILA appends ("Chicken Biryani · Regular"), compared loosely. */
internal fun sameDish(swiggyLine: String, zomato: String): Boolean {
    val a = swiggyLine.substringBefore(" · ").lowercase().replace(Regex("""[^a-z0-9 ]"""), "").trim()
    val b = zomato.lowercase().replace(Regex("""[^a-z0-9 ]"""), "").trim()
    return a.isNotBlank() && (a == b || a.contains(b) || b.contains(a))
}

private const val AREA_MIN = 3
private val GENERIC_PLACES = setOf("india", "karnataka", "kerala", "bengaluru", "bangalore", "thiruvananthapuram", "ernakulam")

/**
 * A dish word reduced to its consonants (no vowels, y or h), so "biriyani", "biryani" and "briyani" agree,
 * as do "mutton" and "muttan". Only words of four letters or more count.
 */
private fun dishSkeleton(word: String) = word.lowercase().filter { it.isLetter() && it !in "aeiouyh" }.replace(Regex("""(.)\1+"""), "$1")

private val notDishWords = setOf("order", "want", "need", "some", "from", "with", "please", "near", "best", "cheap", "food")

/** True when [name] is one of the dishes [query] asks for: "Hyderabadi Chicken Biryani" for "biriyani". */
internal fun mentionsDish(name: String, query: String): Boolean {
    val wanted = query.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length >= 4 && it !in notDishWords }.map(::dishSkeleton)
    if (wanted.isEmpty()) return false
    val words = name.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length >= 4 }.map(::dishSkeleton)
    return wanted.any { w -> words.any { it == w } }
}

/** "₹20 cheaper on Zomato", "₹15 cheaper on Swiggy" or "Same price on Zomato". */
internal fun priceGap(swiggy: Int, zomato: Int): String = when {
    zomato < swiggy -> "₹${swiggy - zomato} cheaper on Zomato"
    zomato > swiggy -> "₹${zomato - swiggy} cheaper on Swiggy"
    else -> "Same price on Zomato"
}

/** The Zomato dish that is this Swiggy dish at the same shop, if Zomato lists it. */
internal fun ZomatoShop.sameAs(swiggyName: String): ZomatoDish? = dishes.firstOrNull { sameDish(swiggyName, it.name) }
