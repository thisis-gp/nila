package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Zomato's match for the cart: every line found, or why not. */
sealed interface ZomatoComparison

/** One cart line found on Zomato, with the variant Zomato's cart needs. [price] is in paise. */
data class ZomatoLine(val name: String, val variantId: String, val quantity: Int, val price: Long)

/** The same order on Zomato, ready for [ZomatoCompare.total] to read the real bill. */
data class ZomatoQuote(
    val restaurantName: String,
    val restaurantId: Long,
    val addressId: String,
    val eta: String,
    val lines: List<ZomatoLine>,
) : ZomatoComparison

/** Why there is no comparison. [failed] means Zomato didn't answer; otherwise the order isn't there. */
data class ZomatoGap(val reason: String, val failed: Boolean = false) : ZomatoComparison

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
 * Prices a cart on Zomato through Zomato's MCP server. Everything is read-only except [total], which
 * creates a Zomato cart to read its bill and never checks out.
 */
internal class ZomatoCompare(private val context: Context) {
    private val lock = Any()
    private var client: McpClient? = null
    private var clientToken: String? = null
    // Per address and per shop, so scrolling back to a card never asks Zomato again.
    private val addressIds = ConcurrentHashMap<String, String>()
    private val shops = ConcurrentHashMap<String, ZomatoShop>()
    private val nearbyCache = ConcurrentHashMap<String, ZomatoNearby>()
    private val addressLock = Mutex()

    // At most three Zomato calls at once: the dish list looks up several shops in parallel.
    private val calls = Semaphore(MAX_PARALLEL_CALLS)

    /** One client per access token, made under a lock so parallel quotes never race to create two. */
    private fun client(token: String): McpClient = synchronized(lock) {
        client?.takeIf { clientToken == token } ?: McpClient(ENDPOINT, token).also {
            client = it
            clientToken = token
        }
    }

    /** Interruptible, so a provider timeout really stops the blocking HTTP call. */
    private suspend fun call(tool: String, args: JSONObject = JSONObject()): JSONObject? = calls.withPermit {
        runInterruptible(Dispatchers.IO) {
            val session = ZomatoAuth.currentSession(context) ?: return@runInterruptible null
            client(session.accessToken).callTool(tool, args)
        }
    }

    suspend fun quote(cart: CartRequest): ZomatoComparison {
        val addresses = call("get_saved_addresses_for_user")?.optJSONArray("addresses") ?: return ZomatoGap(NO_ANSWER, failed = true)
        val match = closestAddress(cart.address, addresses) ?: return ZomatoGap("No saved Zomato address near ${cart.address.label}")
        val addressId = match.pick("address_id") ?: return ZomatoGap("No saved Zomato address near ${cart.address.label}")

        val search = call("get_restaurants_for_keyword", JSONObject().put("address_id", addressId).put("keyword", cart.restaurant).put("page_size", SEARCH_SIZE))
            ?: return ZomatoGap(NO_ANSWER, failed = true)
        val found = allObjects(search).firstOrNull { it.has("res_id") && sameRestaurant(cart.restaurant, it.pick("name").orEmpty()) }
            ?: return ZomatoGap("${cart.restaurant} isn't on Zomato")
        val resId = found.pick("res_id")?.toLongOrNull() ?: return ZomatoGap("${cart.restaurant} isn't on Zomato")
        val place = found.pick("name").orEmpty()

        val listing = call("get_menu_items_listing", JSONObject().put("res_id", resId).put("address_id", addressId))
            ?.let { allObjects(it) }?.filter { it.has("item_name") && it.has("variant_id") }
            ?: return ZomatoGap(NO_ANSWER, failed = true)
        val wanted = cart.lines.associateWith { line -> pickVariant(line, listing.filter { sameDish(line.dish, it.pick("item_name").orEmpty()) }) }
        wanted.entries.firstOrNull { it.value == null }?.let { return ZomatoGap("${it.key.dish} isn't on $place's Zomato menu") }
        val categories = wanted.values.mapNotNull { it?.optJSONArray("categories")?.optString(0) }.distinct()
        if (categories.isEmpty()) return ZomatoGap("$place on Zomato doesn't list these dishes")

        val menu = call(
            "get_restaurant_menu_by_categories",
            JSONObject().put("res_id", resId).put("address_id", addressId).put("categories", JSONArray(categories)),
        )?.let { allObjects(it) }?.filter { it.has("variant_id") && it.has("price") } ?: return ZomatoGap(NO_ANSWER, failed = true)
        val lines = wanted.map { (line, mapped) ->
            val item = menu.firstOrNull { it.pick("variant_id") == mapped?.pick("variant_id") }
            val price = paiseOf(item?.pick("discounted_price", "price")) ?: return ZomatoGap("${line.dish} has no price on Zomato")
            ZomatoLine(item?.pick("name").orEmpty(), item?.pick("variant_id").orEmpty(), line.quantity, price)
        }
        return ZomatoQuote(place, resId, addressId, found.pick("eta").orEmpty(), lines)
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

    /** Creates a Zomato cart for the same items to read its real bill, fees included. Never checks out. */
    suspend fun total(quote: ZomatoQuote): ProviderQuote {
        val items = JSONArray()
        quote.lines.forEach { items.put(JSONObject().put("variant_id", it.variantId).put("quantity", it.quantity)) }
        val result = call(
            "create_cart",
            JSONObject().put("res_id", quote.restaurantId).put("items", items).put("address_id", quote.addressId).put("payment_type", "upi"),
        ) ?: return ProviderQuote.Failed(NO_ANSWER)
        return zomatoBill(result, quote.lines.sumOf { it.price * it.quantity }, quote.eta)
    }

    private companion object {
        const val ENDPOINT = "https://mcp-server.zomato.com/mcp"
        const val SEARCH_SIZE = 5
        // Each shop costs two menu calls, so the dish list reads the first few shops only.
        const val NEARBY_SHOPS = 4
        const val MAX_PARALLEL_CALLS = 3
    }
}

/** Zomato's create_cart answer as a bill. Its total is required; fee lines it doesn't name are reconciled as fees. */
internal fun zomatoBill(result: JSONObject, itemTotal: Long, eta: String): ProviderQuote {
    fun amount(vararg keys: String) = keys.firstNotNullOfOrNull { findString(result, it) }?.let(::paiseOf) ?: 0L
    val total = findString(result, "total_amount")?.let(::paiseOf) ?: return ProviderQuote.Failed("Zomato sent no total")
    val bill = Bill(
        itemTotal = itemTotal,
        delivery = amount("delivery_charge", "delivery_fee"),
        platformFee = amount("platform_fee"),
        taxes = amount("taxes", "total_tax", "gst"),
        discount = amount("discount", "total_discount"),
        total = total,
    )
    return ProviderQuote.Priced(bill.reconciled(), digitsOf(eta), coupon = null)
}

/**
 * A Zomato address on the same pincode as NILA's chosen one. Never a city or area guess: Zomato's MCP can't
 * save addresses, so a missing match is said plainly.
 */
internal fun closestAddress(address: DeliveryAddress, zomato: JSONArray): JSONObject? {
    val pin = address.pincode ?: return null
    val exact = Regex("""\b$pin\b""")
    return (0 until zomato.length()).mapNotNull { zomato.optJSONObject(it) }.firstOrNull { saved ->
        saved.keys().asSequence().filterNot { it.contains("id") }.any { key -> saved.pick(key)?.let(exact::containsMatchIn) == true }
    }
}

internal fun addressLookup(address: FoodAddress, response: JSONObject?): ZomatoAddress {
    val saved = response?.optJSONArray("addresses")
    val id = saved?.let { closestAddress(deliveryAddress(address), it)?.pick("address_id") }
    return addressResult(response != null && saved != null, id)
}

internal fun addressResult(answered: Boolean, id: String?): ZomatoAddress = when {
    !answered -> ZomatoAddress.Failed
    id == null -> ZomatoAddress.Empty
    else -> ZomatoAddress.Found(id)
}

/** "Paradise Biryani" matches "Paradise Biryani - A Legend Since 1953"; brand words must agree. */
internal fun sameRestaurant(swiggy: String, zomato: String): Boolean {
    val a = swiggy.lowercase().substringBefore(" - ").trim()
    val b = zomato.lowercase().substringBefore(" - ").trim()
    return a.isNotBlank() && (a == b || b.startsWith(a) || a.startsWith(b))
}

/**
 * The same dish only when the names are equal once size words, brackets and punctuation are gone.
 * "Chicken Biryani" never matches "Biryani": a near miss would show a false "cheaper".
 */
internal fun sameDish(swiggy: String, zomato: String): Boolean {
    val a = dishKey(swiggy)
    return a.isNotBlank() && a == dishKey(zomato)
}

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

/**
 * The Zomato dish that is exactly this Swiggy dish at the same shop. Only an exact name match carries a
 * price verdict: "Biryani" against "Chicken Biryani" would claim a false "₹X cheaper".
 */
internal fun ZomatoShop.sameAs(swiggyName: String): ZomatoDish? = dishes.firstOrNull { dishKey(it.name) == dishKey(swiggyName) }

internal const val NO_ANSWER = "Zomato didn't answer"

/**
 * Several Zomato entries for one dish are its sizes: take the one naming the user's size, else none.
 * A single entry that names a different size ("Half" against the user's "Full") is not the same order.
 */
internal fun pickVariant(line: CartLine, candidates: List<JSONObject>): JSONObject? {
    val wanted = line.variant?.let(::sizeIn)
    if (candidates.size == 1) {
        val theirs = sizeIn(variantText(candidates[0]))
        return candidates[0].takeIf { wanted == null || theirs == null || theirs == wanted }
    }
    wanted ?: return null
    return candidates.firstOrNull { sizeIn(variantText(it)) == wanted }
}

private fun variantText(entry: JSONObject) =
    listOfNotNull(entry.pick("variant_name"), entry.pick("size"), entry.pick("name"), entry.pick("item_name")).joinToString(" ")

private fun sizeIn(text: String): String? {
    val words = text.lowercase().split(Regex("""[^a-z]+"""))
    return SIZE_WORDS.firstOrNull { it in words }
}

/** "Chicken Biryani (Half) · Regular" and "chicken biryani" are the same name; "Biryani" is not. */
internal fun dishKey(name: String): String =
    name.substringBefore(" · ").lowercase()
        .replace(Regex("""\(.*?\)"""), " ")
        .replace(Regex("""[^\p{L}\p{N} ]"""), " ")
        .split(" ").filter { it.isNotBlank() && it !in SIZE_WORDS }
        .joinToString(" ")

private val SIZE_WORDS = setOf("regular", "small", "medium", "large", "half", "full", "mini", "quarter", "portion", "plate")
