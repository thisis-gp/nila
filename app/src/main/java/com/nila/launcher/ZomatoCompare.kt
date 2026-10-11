package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
data class ZomatoShop(val name: String, val dishes: List<ZomatoDish>, val gap: String? = null, val eta: String = "", val rating: String = "")

/** Zomato's answer for a dish near an address: shops with that dish, cheapest first, or why there are none. */
/** [checked] is how many Zomato shops were read, so the card can say "cheapest of the N we checked". */
data class ZomatoNearby(val shops: List<ZomatoShop>, val gap: String? = null, val checked: Int = 0)

internal sealed interface ZomatoAddress {
    data class Found(val id: String) : ZomatoAddress
    data object Empty : ZomatoAddress
    data object Failed : ZomatoAddress
}

/**
 * Prices the user's Swiggy cart on Zomato through Zomato's MCP server. Every step is read-only except
 * [total], which creates a Zomato cart (never checks out) and runs only from the user's tap.
 */
internal class ZomatoCompare(private val context: Context) {
    private var client: McpClient? = null
    // Per address and per shop, so scrolling back to a card never asks Zomato again.
    private val addressIds = ConcurrentHashMap<String, String>()
    private val shops = ConcurrentHashMap<String, ZomatoShop>()
    private val nearbyCache = ConcurrentHashMap<String, ZomatoNearby>()
    private val addressLock = Mutex()

    val connected: Boolean get() = ZomatoAuth.currentSession(context) != null

    // At most three Zomato calls at once: the dish list looks up several shops in parallel.
    private val calls = Semaphore(MAX_PARALLEL_CALLS)
    private val clientLock = Mutex()
    private suspend fun call(tool: String, args: JSONObject = JSONObject()): JSONObject? = calls.withPermit {
        val session = ZomatoAuth.currentSession(context) ?: return@withPermit null
        val c = clientLock.withLock { client ?: McpClient(ENDPOINT, session.accessToken).also { client = it } }
        withContext(Dispatchers.IO) { c.callTool(tool, args) }
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

    private suspend fun addressIdFor(address: FoodAddress): ZomatoAddress = addressLock.withLock {
        addressIds[address.id]?.let { return ZomatoAddress.Found(it) }
        return addressLookup(address, call("get_saved_addresses_for_user")).also { result ->
            if (result is ZomatoAddress.Found) addressIds[address.id] = result.id
        }
    }

    /**
     * The shop the user is looking at on Swiggy, found on Zomato, with every dish on its menu that matches
     * [query] ("biryani" finds Chicken, Mutton and Egg Biryani) and its Zomato price. Read-only.
     */
    suspend fun shop(address: FoodAddress, restaurantName: String, query: String): ZomatoShop {
        val key = "${address.id}|$restaurantName|$query"
        shops[key]?.let { return it }
        fun gap(reason: String) = ZomatoShop(restaurantName, emptyList(), reason)
        // A failed call is "Zomato didn't answer", never "Not on Zomato", and is not cached: the next look tries again.
        val unanswered = gap(NO_ANSWER)
        val addressId = when (val result = addressIdFor(address)) {
            ZomatoAddress.Failed -> return unanswered
            ZomatoAddress.Empty -> return gap("No Zomato address near ${address.label}")
            is ZomatoAddress.Found -> result.id
        }
        val results = call("get_restaurants_for_keyword", JSONObject().put("address_id", addressId).put("keyword", restaurantName).put("page_size", SEARCH_SIZE))
            ?: return unanswered
        val found = allObjects(results).firstOrNull { it.has("res_id") && sameRestaurant(restaurantName, it.pick("name").orEmpty()) }
            ?: return gap("Not on Zomato near ${address.label}").also { shops[key] = it }
        return when (val menu = menuOf(addressId, found, query, restaurantName)) {
            MenuResult.Failed -> unanswered
            MenuResult.Empty -> gap("No $query on its Zomato menu").also { shops[key] = it }
            is MenuResult.Found -> menu.shop.also { shops[key] = it }
        }
    }

    /**
     * The dish across Zomato near [address], like the Swiggy dish list: Zomato has no dish search, so the shops
     * it finds for the dish are read for their matching dishes, a few at a time. Read-only. One shop failing
     * leaves the others; a failed search says so instead of "found nothing". Cached per address and dish.
     */
    suspend fun nearby(address: FoodAddress, query: String): ZomatoNearby {
        val addressId = when (val result = addressIdFor(address)) {
            ZomatoAddress.Failed -> return ZomatoNearby(emptyList(), NO_ANSWER)
            ZomatoAddress.Empty -> return ZomatoNearby(emptyList(), "No Zomato address near ${address.label}")
            is ZomatoAddress.Found -> result.id
        }
        val key = "$addressId|$query"
        nearbyCache[key]?.let { return it }
        val results = call("get_restaurants_for_keyword", JSONObject().put("address_id", addressId).put("keyword", query).put("page_size", SEARCH_SIZE))
            ?: return ZomatoNearby(emptyList(), NO_ANSWER)
        val found = allObjects(results).filter { it.has("res_id") && it.pick("name") != null }.distinctBy { it.pick("res_id") }.take(NEARBY_SHOPS)
        if (found.isEmpty()) return ZomatoNearby(emptyList(), "Zomato found no $query near ${address.label}").also { nearbyCache[key] = it }
        val menus = coroutineScope {
            found.map { shop ->
                async {
                    try {
                        menuOf(addressId, shop, query, shop.pick("name").orEmpty())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        MenuResult.Failed
                    }
                }
            }.awaitAll()
        }
        val shopsFound = menus.filterIsInstance<MenuResult.Found>().map { it.shop }.sortedBy { it.dishes.first().price }
        return when {
            shopsFound.isNotEmpty() -> ZomatoNearby(shopsFound, checked = found.size).also { nearbyCache[key] = it }
            menus.all { it == MenuResult.Failed } -> ZomatoNearby(emptyList(), NO_ANSWER)
            else -> ZomatoNearby(emptyList(), "Zomato's shops near ${address.label} don't list $query").also { nearbyCache[key] = it }
        }
    }

    private sealed interface MenuResult {
        data object Failed : MenuResult
        data object Empty : MenuResult
        data class Found(val shop: ZomatoShop) : MenuResult
    }

    /** One Zomato shop's dishes matching [query], priced; Empty when it lists none, Failed when Zomato didn't answer. */
    private suspend fun menuOf(addressId: String, found: JSONObject, query: String, fallbackName: String): MenuResult {
        val resId = found.pick("res_id")?.toLongOrNull() ?: return MenuResult.Empty
        val listing = (call("get_menu_items_listing", JSONObject().put("res_id", resId).put("address_id", addressId)) ?: return MenuResult.Failed)
            .let { allObjects(it) }.filter { it.has("item_name") && it.has("variant_id") }
            .filter { mentionsDish(it.pick("item_name").orEmpty(), query) }
        val categories = listing.mapNotNull { it.optJSONArray("categories")?.optString(0) }.distinct()
        if (categories.isEmpty()) return MenuResult.Empty
        val wanted = listing.mapNotNull { it.pick("variant_id") }.toSet()
        val menu = call(
            "get_restaurant_menu_by_categories",
            JSONObject().put("res_id", resId).put("address_id", addressId).put("categories", JSONArray(categories)),
        ) ?: return MenuResult.Failed
        val dishes = allObjects(menu)
            .filter { it.pick("variant_id") in wanted }
            .mapNotNull { item ->
                val price = item.pick("discounted_price", "price")?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                ZomatoDish(item.pick("name", "item_name").orEmpty(), price)
            }
            // Size variants share a name but not a price: keep each one.
            .distinctBy { it.name.lowercase() to it.price }
            .sortedBy { it.price }
        if (dishes.isEmpty()) return MenuResult.Empty
        return MenuResult.Found(
            ZomatoShop(
                name = found.pick("name").orEmpty().ifBlank { fallbackName },
                dishes = dishes,
                eta = found.pick("eta", "delivery_time").orEmpty(),
                rating = found.pick("rating", "aggregate_rating").orEmpty(),
            ),
        )
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
        // Each shop costs two menu calls, so the dish list reads the first few shops only.
        const val NEARBY_SHOPS = 4
        const val MAX_PARALLEL_CALLS = 3
    }
}

internal fun addressLookup(address: FoodAddress, response: JSONObject?): ZomatoAddress {
    val saved = response?.optJSONArray("addresses")
    val id = saved?.let { closestAddress(address, it)?.pick("address_id") }
    return addressResult(response != null && saved != null, id)
}

internal fun addressResult(answered: Boolean, id: String?): ZomatoAddress = when {
    !answered -> ZomatoAddress.Failed
    id == null -> ZomatoAddress.Empty
    else -> ZomatoAddress.Found(id)
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

/** True when [name] is the dish [query] asks for, its words in order: "Hyderabadi Chicken Biryani" for "biriyani". */
internal fun mentionsDish(name: String, query: String): Boolean {
    val wanted = query.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length >= 4 && it !in notDishWords }.map(::dishSkeleton)
    if (wanted.isEmpty()) return false
    val words = name.lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length >= 4 }.map(::dishSkeleton)
    // In order, at most one word apart: "Butter Chicken Kulcha" and "Butter Paneer Chicken" are butter chicken,
    // "Stir Fried Chicken in a Garlic Butter Sauce" is not.
    fun matchesFrom(start: Int): Boolean {
        if (words[start] != wanted.first()) return false
        var at = start
        for (w in wanted.drop(1)) {
            at = (at + 1..minOf(at + 2, words.lastIndex)).firstOrNull { words[it] == w } ?: return false
        }
        return true
    }
    return words.indices.any(::matchesFrom)
}

/** "₹20 cheaper on Zomato", "₹15 cheaper on Swiggy" or "Same price on Zomato". */
internal fun priceGap(swiggy: Int, zomato: Int): String = when {
    zomato < swiggy -> "₹${swiggy - zomato} cheaper on Zomato"
    zomato > swiggy -> "₹${zomato - swiggy} cheaper on Swiggy"
    else -> "Same price on Zomato"
}

/** "Chicken Biryani · Regular" and "chicken biryani" are the same name; "Biryani" is not. */
internal fun dishKey(name: String) = name.substringBefore(" · ").lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), " ").replace(Regex("""\s+"""), " ").trim()

/**
 * The Zomato dish that is exactly this Swiggy dish at the same shop. Only an exact name match carries a
 * price verdict: "Biryani" against "Chicken Biryani" would claim a false "₹X cheaper".
 */
internal fun ZomatoShop.sameAs(swiggyName: String): ZomatoDish? = dishes.firstOrNull { dishKey(it.name) == dishKey(swiggyName) }

internal const val NO_ANSWER = "Zomato didn't answer. Try again in a moment."
