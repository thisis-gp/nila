package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FoodAddress(val id: String, val label: String, val line: String)

data class FoodRestaurant(
    val id: String,
    val name: String,
    val cuisines: String,
    val rating: String,
    val eta: String,
    val distance: String,
    val costForTwo: String,
    val imageUrl: String?,
    val offer: String,
    val open: Boolean,
    val area: String = "",
    val ratingCount: String = "",
    /** Swiggy marks promoted listings "(Ad)" in the name; NILA shows that as a tag instead. */
    val ad: Boolean = false,
)

data class FoodItem(
    val id: String,
    val name: String,
    val price: String,
    val veg: Boolean?,
    val imageUrl: String?,
    val description: String,
    val customizable: Boolean,
    /** The item exactly as Swiggy returned it, needed to send its variant IDs back to the cart. */
    val raw: String,
    /** Options of the item's first variant group (usually Size), cheapest first as Swiggy lists them. */
    val sizes: List<FoodSize> = emptyList(),
    val chosenSize: FoodSize? = null,
)

data class FoodSize(val groupId: String, val id: String, val name: String, val price: String, val isDefault: Boolean)

/** A dish from a search across restaurants, with the place it comes from and why NILA ranked it there. */
data class FoodDish(val item: FoodItem, val restaurant: FoodRestaurant, val reason: String)

data class FoodCartLine(val itemId: String, val name: String, val quantity: Int, val price: String)

data class FoodCartView(
    val restaurantName: String,
    val lines: List<FoodCartLine>,
    val bill: List<Pair<String, String>>,
    val total: String,
)

data class FoodPayOption(val label: String, val method: String, val intentApp: String?, val iconUrl: String? = null)

/** A Swiggy offer for this cart. [usable] is Swiggy's own applicability answer for this cart and address. */
data class FoodCoupon(val code: String, val title: String, val saving: String, val usable: Boolean)

data class NewAddressForm(
    val house: String,
    val street: String,
    val city: String,
    val pincode: String,
    val name: String,
    val phone: String,
    val category: String,
)

/** Everything Swiggy needs echoed back to check and finalize a UPI payment. */
data class PendingPayment(
    val paasId: String?,
    val orderId: String?,
    val addressId: String,
    val cartId: String?,
    val lat: String?,
    val lng: String?,
    val upiLink: String?,
)

sealed interface FoodCard {
    /** [here] is null when location is off or not allowed; [addresses] are ranked nearest first. */
    data class Addresses(
        val query: String,
        val addresses: List<FoodAddress>,
        val here: CurrentPlace?,
        val locationAllowed: Boolean,
        val intent: NilaResponse.FoodSearch = NilaResponse.FoodSearch(query),
    ) : FoodCard
    /** [picks] are the best fits for [intent] with the reason each was picked; [restaurants] is everything Swiggy returned. */
    data class Restaurants(
        val address: FoodAddress,
        val query: String,
        val restaurants: List<FoodRestaurant>,
        val intent: NilaResponse.FoodSearch,
        val picks: List<Pair<FoodRestaurant, String>>,
    ) : FoodCard
    data class Menu(val address: FoodAddress, val restaurant: FoodRestaurant, val items: List<FoodItem>) : FoodCard
    /** The dish the user asked for, from every nearby place that has it. Restaurants stay one tap away. */
    data class Dishes(
        val address: FoodAddress,
        val query: String,
        val intent: NilaResponse.FoodSearch,
        val dishes: List<FoodDish>,
    ) : FoodCard
    data class Cart(val address: FoodAddress, val restaurant: FoodRestaurant, val cart: FoodCartView) : FoodCard
    data class Payment(
        val address: FoodAddress,
        val restaurant: FoodRestaurant,
        val cart: FoodCartView,
        val options: List<FoodPayOption>,
        val coupons: List<FoodCoupon> = emptyList(),
        val applied: FoodCoupon? = null,
        val zomato: ZomatoComparison? = null,
    ) : FoodCard
    data class Order(
        val title: String,
        val detail: String,
        val orderId: String?,
        val pending: PendingPayment?,
        val placed: Boolean,
    ) : FoodCard
    data class Problem(val message: String) : FoodCard
}

/**
 * The live Swiggy order flow: address → restaurants → menu → cart → payment → order.
 * Each step returns one card for the chat. Read and Prepare steps run freely; [place]
 * is the only Commit step and the UI only calls it after an explicit second tap.
 * Swiggy doesn't document response shapes, so parsers search the JSON tree by key
 * names instead of fixed paths, and every raw response is kept in files/probe/.
 */
internal class FoodFlow(private val context: Context) {
    private var client: McpClient? = null
    private val zomato = ZomatoCompare(context)
    private var cartRestaurantId: String? = null
    private val cartItems = linkedMapOf<String, Pair<FoodItem, Int>>()

    val connected: Boolean get() = SwiggyAuth.currentSession(context) != null

    /** What the user asked for, as the router and NilaEngine read it. Chips on the cards edit it. */
    private var intent = NilaResponse.FoodSearch("")
    private val memory = context.getSharedPreferences("nila_food", Context.MODE_PRIVATE)

    /** The cards a Back link returns to, shown again without asking Swiggy. */
    var lastResults: FoodCard.Restaurants? = null
        private set
    /** Where "Add more" goes from the cart: the dish list or the restaurant menu the user added from. */
    var lastMenu: FoodCard? = null
        private set
    var lastCart: FoodCard.Cart? = null
        private set
    var lastPayment: FoodCard.Payment? = null
        private set

    private suspend fun call(tool: String, args: JSONObject = JSONObject()): JSONObject? = withContext(Dispatchers.IO) {
        val session = SwiggyAuth.currentSession(context) ?: return@withContext null
        val c = client ?: McpClient(SWIGGY_FOOD_ENDPOINT, session.accessToken).also { client = it }
        c.callTool(tool, args).also { result ->
            runCatching {
                val dir = File(context.filesDir, "probe").apply { mkdirs() }
                File(dir, "$tool.json").writeText(result?.toString(2) ?: "ERR ${c.lastError}")
            }
        }
    }

    private fun card(card: FoodCard) = NilaResponse.Food(card)

    private fun problem(step: String) =
        card(FoodCard.Problem("$step didn't work: ${client?.lastError ?: "Swiggy is not connected."}"))

    suspend fun start(search: NilaResponse.FoodSearch): NilaResponse {
        intent = search
        return start(search.query)
    }

    /**
     * Straight to the best fits: NILA picks the address the user last ordered to, else a saved one near
     * where the phone is, and shows it as one "To Hotel2 · Change" line. Only with nothing to go on
     * does the user get the address picker.
     */
    suspend fun start(query: String): NilaResponse {
        if (intent.query != query) intent = NilaResponse.FoodSearch(query)
        val here = CurrentPlaces.locate(context)
        val result = call("get_addresses") ?: return problem("Loading your addresses")
        val addresses = parseAddresses(result)
        val last = memory.getString(LAST_ADDRESS, null)
        val chosen = addresses.firstOrNull { it.id == last }
            ?: here?.let { spot -> addresses.firstOrNull { CurrentPlaces.matchScore(it, spot) >= CurrentPlaces.NEARBY_SCORE } }
            ?: return card(FoodCard.Addresses(query, CurrentPlaces.rank(addresses, here), here, CurrentPlaces.allowed(context), intent))
        return restaurants(chosen, query)
    }

    suspend fun chooseAddress(query: String): NilaResponse {
        val here = CurrentPlaces.locate(context)
        val result = call("get_addresses") ?: return problem("Loading your addresses")
        val addresses = CurrentPlaces.rank(parseAddresses(result), here)
        if (intent.query != query) intent = NilaResponse.FoodSearch(query)
        return card(FoodCard.Addresses(query, addresses, here, CurrentPlaces.allowed(context), intent))
    }

    suspend fun addAddress(query: String, form: NewAddressForm): NilaResponse {
        val full = listOf(form.house, form.street, form.city, form.pincode).filter { it.isNotBlank() }.joinToString(", ")
        val args = JSONObject()
            .put("fullAddress", full)
            .put("addressLine", form.street.ifBlank { form.house })
            .put("addressLine2", form.house)
            .put("city", form.city)
            .put("postalCode", form.pincode)
            .put("addressCategory", form.category)
            .put("userName", form.name)
            .put("userPhone", form.phone)
            .put("addressTag", form.house)
        val created = call("create_address", args)?.let { findString(it, "addressId") } ?: return problem("Saving the address")
        // Deliver to the address just saved; restarting from the last-used one showed "To Hotel2" after adding WeWork.
        val saved = call("get_addresses")?.let(::parseAddresses).orEmpty().firstOrNull { it.id == created }
            ?: FoodAddress(created, form.house, full)
        return deliverTo(saved, query)
    }

    suspend fun refine(address: FoodAddress, search: NilaResponse.FoodSearch): NilaResponse {
        intent = search
        return restaurants(address, search.query)
    }

    suspend fun deliverTo(address: FoodAddress, query: String): NilaResponse {
        memory.edit().putString(LAST_ADDRESS, address.id).apply()
        return restaurants(address, query)
    }

    suspend fun restaurants(address: FoodAddress, query: String): NilaResponse {
        // A taste word ("light", "spicy") goes into Swiggy's search; if that finds nothing, the dish alone.
        // The dish first: "paneer roll" means paneer rolls from every place nearby, not a list of restaurants.
        val dishResult = call("search_menu", JSONObject().put("query", query).put("addressId", address.id))
        val worded = listOfNotNull(intent.preference, query).joinToString(" ")
        var list = call("search_restaurants", JSONObject().put("query", worded).put("addressId", address.id))
            ?.let(::parseRestaurants) ?: return problem("Searching restaurants")
        if (list.isEmpty() && worded != query) {
            list = call("search_restaurants", JSONObject().put("query", query).put("addressId", address.id))
                ?.let(::parseRestaurants).orEmpty()
        }
        if (list.isEmpty()) return card(FoodCard.Problem("No restaurants found for \"$query\" near ${address.label}."))
        val results = FoodCard.Restaurants(address, query, list, intent, bestFits(list, intent)).also { lastResults = it }
        val dishes = dishResult?.let { rankDishes(parseDishes(it, list), intent) }.orEmpty()
        if (dishes.isEmpty()) return card(results)
        return card(FoodCard.Dishes(address, query, intent, dishes).also { lastMenu = it })
    }

    suspend fun menu(address: FoodAddress, restaurant: FoodRestaurant, query: String): NilaResponse {
        // search_menu scoped to the restaurant returns the variant IDs the cart needs;
        // the full menu browse is only a fallback when the dish search comes back empty.
        var result = call(
            "search_menu",
            JSONObject().put("query", query).put("addressId", address.id).put("restaurantIdOfAddedItem", restaurant.id),
        )
        var items = result?.let(::parseMenuItems).orEmpty()
        if (items.isEmpty()) {
            result = call("get_restaurant_menu", JSONObject().put("restaurantId", restaurant.id).put("addressId", address.id))
            items = result?.let(::parseMenuItems).orEmpty()
        }
        if (result == null) return problem("Loading the menu")
        if (items.isEmpty()) return card(FoodCard.Problem("Couldn't read ${restaurant.name}'s menu."))
        return card(FoodCard.Menu(address, restaurant, items).also { lastMenu = it })
    }

    /** Adds or removes one unit; the full cart is sent each time so Swiggy's cart matches what NILA shows. */
    suspend fun changeItem(address: FoodAddress, restaurant: FoodRestaurant, item: FoodItem, delta: Int): NilaResponse {
        if (cartRestaurantId != restaurant.id) {
            cartItems.clear()
            cartRestaurantId = restaurant.id
        }
        // A different size replaces the line: NILA keeps one size per dish.
        val previous = cartItems[item.id]?.takeIf { (it.first.chosenSize ?: item.chosenSize) == item.chosenSize || delta < 0 }
        val quantity = (previous?.second ?: 0) + delta
        if (quantity <= 0) cartItems.remove(item.id) else cartItems[item.id] = (if (delta < 0) previous?.first ?: item else item) to quantity
        if (cartItems.isEmpty()) {
            call("flush_food_cart")
            return card(FoodCard.Problem("Your cart is empty."))
        }
        val lines = JSONArray()
        cartItems.values.forEach { (cartItem, count) -> lines.put(cartItemJson(cartItem, count)) }
        call(
            "update_food_cart",
            JSONObject()
                .put("restaurantId", restaurant.id)
                .put("cartItems", lines)
                .put("addressId", address.id)
                .put("restaurantName", restaurant.name),
        ) ?: return problem("Updating the cart")
        return cart(address, restaurant)
    }

    suspend fun changeLine(address: FoodAddress, restaurant: FoodRestaurant, itemId: String, delta: Int): NilaResponse {
        val item = cartItems[itemId]?.first ?: return card(FoodCard.Problem("That item isn't in NILA's cart any more."))
        return changeItem(address, restaurant, item, delta)
    }

    suspend fun cart(address: FoodAddress, restaurant: FoodRestaurant): NilaResponse {
        val result = call("get_food_cart", JSONObject().put("addressId", address.id).put("restaurantName", restaurant.name))
            ?: return problem("Loading the cart")
        return card(FoodCard.Cart(address, restaurant, parseCart(result, restaurant.name)).also { lastCart = it })
    }

    suspend fun payment(address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView): NilaResponse {
        val result = call("get_payment_options", JSONObject().put("addressId", address.id))
            ?: return problem("Loading payment options")
        val options = parsePayOptions(result)
        val usual = memory.getString(LAST_PAYMENT, null)
        // The method used last time comes first, so checkout is one tap unless the user wants another.
        val ordered = options.sortedByDescending { it.label == usual }
        val coupons = call("fetch_food_coupons", JSONObject().put("restaurantId", restaurant.id).put("addressId", address.id))
            ?.let(::parseCoupons).orEmpty()
        // Same order on Zomato, by dish price, when the user has connected Zomato in Settings.
        val compared = if (zomato.connected) zomato.quote(address, restaurant, cart) else null
        return card(FoodCard.Payment(address, restaurant, cart, ordered, coupons, zomato = compared).also { lastPayment = it })
    }

    /** The user asked for Zomato's real total: creates a Zomato cart for the same items, never checks out. */
    suspend fun zomatoTotal(payment: FoodCard.Payment, method: String): NilaResponse {
        val quote = payment.zomato as? ZomatoQuote ?: return card(payment)
        val priced = zomato.total(quote, method) ?: return card(payment.copy(zomato = ZomatoGap("Zomato couldn't price this cart right now")))
        return card(payment.copy(zomato = priced).also { lastPayment = it })
    }

    /** Applies one coupon; Swiggy answers with the repriced cart, which replaces the bill on the same card. */
    suspend fun applyCoupon(payment: FoodCard.Payment, coupon: FoodCoupon): NilaResponse {
        val result = call("apply_food_coupon", JSONObject().put("couponCode", coupon.code).put("addressId", payment.address.id))
            ?: return problem("Applying ${coupon.code}")
        val repriced = parseCart(result, payment.restaurant.name).takeIf { it.lines.isNotEmpty() }
            ?: parseCart(call("get_food_cart", JSONObject().put("addressId", payment.address.id).put("restaurantName", payment.restaurant.name)) ?: result, payment.restaurant.name)
        return card(payment.copy(cart = repriced, applied = coupon).also { lastPayment = it })
    }

    /** Commit: places a real Swiggy order. Only reachable from the two-tap confirmation. */
    suspend fun place(address: FoodAddress, cart: FoodCartView, option: FoodPayOption): NilaResponse {
        memory.edit().putString(LAST_PAYMENT, option.label).apply()
        val args = JSONObject().put("addressId", address.id).put("paymentMethod", option.method)
        option.intentApp?.let { args.put("intentApp", it) }
        val result = call("place_food_order", args) ?: return problem("Placing the order")
        cartItems.clear()
        val status = findString(result, "status").orEmpty()
        val pending = PendingPayment(
            paasId = findString(result, "paasId"),
            orderId = findString(result, "orderId"),
            addressId = address.id,
            cartId = findString(result, "cartId"),
            lat = findString(result, "lat"),
            lng = findString(result, "lng"),
            upiLink = findUpiLink(result),
        )
        return if (status.contains("PENDING", ignoreCase = true) || option.method.equals("UPI", ignoreCase = true)) {
            card(
                FoodCard.Order(
                    title = "Pay in your UPI app",
                    detail = "Swiggy places the order only after the payment succeeds. Nothing is ordered yet.",
                    orderId = pending.orderId,
                    pending = pending,
                    placed = false,
                ),
            )
        } else {
            card(
                FoodCard.Order(
                    title = "Swiggy order placed",
                    detail = findString(result, "message") ?: "Cash on delivery · ${cart.total}",
                    orderId = pending.orderId,
                    pending = null,
                    placed = true,
                ),
            )
        }
    }

    suspend fun checkPayment(p: PendingPayment): NilaResponse {
        val args = JSONObject().put("paasId", p.paasId.orEmpty()).put("addressId", p.addressId)
        p.orderId?.let { args.put("orderId", it) }
        p.cartId?.let { args.put("cartId", it) }
        p.lat?.let { args.put("lat", it) }
        p.lng?.let { args.put("lng", it) }
        val result = call("check_payment_status", args) ?: return problem("Checking the payment")
        val status = (findString(result, "status") ?: findString(result, "paymentStatus")).orEmpty().uppercase()
        return when {
            status.contains("SUCCESS") || status.contains("PAID") -> {
                val confirm = JSONObject().put("orderId", p.orderId.orEmpty()).put("addressId", p.addressId)
                p.paasId?.let { confirm.put("paasId", it) }
                p.cartId?.let { confirm.put("cartId", it) }
                p.lat?.let { confirm.put("lat", it) }
                p.lng?.let { confirm.put("lng", it) }
                call("confirm_order", confirm) ?: return problem("Confirming the order")
                card(FoodCard.Order("Swiggy order placed", "Payment received. Your food is on the way.", p.orderId, null, placed = true))
            }
            status.contains("REFUND") ->
                card(FoodCard.Order("Refund started", "The payment didn't complete, so Swiggy started a refund.", p.orderId, null, placed = false))
            status.contains("FAIL") ->
                card(FoodCard.Order("Payment failed", "Nothing was ordered. Open the cart to try another payment method.", p.orderId, null, placed = false))
            else ->
                card(FoodCard.Order("Payment still processing", "Finish paying in your UPI app, then check again.", p.orderId, p, placed = false))
        }
    }

    suspend fun track(orderId: String?): NilaResponse {
        val result = call("track_food_order", JSONObject().apply { orderId?.let { put("orderId", it) } })
            ?: return problem("Tracking the order")
        val status = listOfNotNull(
            findString(result, "statusMessage") ?: findString(result, "status"),
            findString(result, "eta")?.let { "ETA $it" },
        ).joinToString(" · ").ifBlank { "Swiggy has no live update yet." }
        return card(FoodCard.Order("Order status", status, orderId, null, placed = true))
    }
}

// ---- Tolerant JSON helpers -------------------------------------------------

internal fun JSONObject.pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }
        ?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

internal fun allObjects(node: Any?, out: MutableList<JSONObject> = mutableListOf(), depth: Int = 0): List<JSONObject> {
    if (depth > 10) return out
    when (node) {
        is JSONObject -> {
            out += node
            node.keys().forEach { allObjects(node.opt(it), out, depth + 1) }
        }
        is JSONArray -> for (i in 0 until node.length()) allObjects(node.opt(i), out, depth + 1)
    }
    return out
}

internal fun findString(node: Any?, key: String): String? =
    allObjects(node).firstNotNullOfOrNull { it.pick(key) }

private fun allStrings(node: Any?, out: MutableList<String> = mutableListOf(), depth: Int = 0): List<String> {
    if (depth > 10) return out
    when (node) {
        is JSONObject -> node.keys().forEach { allStrings(node.opt(it), out, depth + 1) }
        is JSONArray -> for (i in 0 until node.length()) allStrings(node.opt(i), out, depth + 1)
        is String -> out += node
    }
    return out
}

/**
 * The app-specific link first: Swiggy answered with upiIntentUrl = "gpay://upi/pay?...", which the old
 * upi:// match missed, so Place order showed no way to pay. bridgeUrl is Swiggy's https fallback.
 */
private fun findUpiLink(node: Any?): String? =
    (node as? JSONObject)?.let { findString(it, "upiIntentUrl") }
        ?: allStrings(node).firstOrNull { it.startsWith("upi://") || it.startsWith("intent://") || it.contains("upi://pay") || it.contains("://upi/pay") }
        ?: (node as? JSONObject)?.let { findString(it, "bridgeUrl") }

/** Swiggy images are either full URLs or Cloudinary IDs on its media CDN. */
private fun imageUrl(o: JSONObject, vararg keys: String): String? {
    val value = o.pick(*keys) ?: return null
    if (value.startsWith("http")) return value
    return "https://media-assets.swiggy.com/swiggy/image/upload/fl_lossy,f_auto,q_auto,w_300/$value"
}

/** Swiggy's internal APIs sometimes give paise; a single dish above ₹3000 is far rarer than a paise value. */
internal fun rupees(value: String?): String {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return ""
    if (text.startsWith("₹")) return text
    val number = text.toDoubleOrNull() ?: return text
    val amount = if (number > 3000 && number % 1.0 == 0.0) number / 100 else number
    return if (amount % 1.0 == 0.0) "₹${amount.toLong()}" else "₹%.2f".format(amount)
}

private fun listText(o: JSONObject, key: String): String? = when (val v = o.opt(key)) {
    is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf(String::isNotBlank) }.joinToString(", ")
    is String -> v
    else -> null
}?.takeIf { it.isNotBlank() }

private fun parseAddresses(result: JSONObject): List<FoodAddress> =
    allObjects(result)
        .filter { o -> o.pick("id", "addressId", "address_id") != null && o.pick("address", "fullAddress", "formattedAddress", "addressLine", "displayAddress", "annotation") != null }
        .mapNotNull { o ->
            val id = o.pick("id", "addressId", "address_id") ?: return@mapNotNull null
            FoodAddress(
                id = id,
                label = o.pick("annotation", "addressTag", "tag", "label", "addressCategory", "category")
                    ?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Address",
                line = o.pick("address", "fullAddress", "formattedAddress", "displayAddress", "addressLine", "area").orEmpty(),
            )
        }
        .distinctBy { it.id }

private const val SWIGGY_FOOD_ENDPOINT = "https://mcp.swiggy.com/food"
private const val BEST_FIT_COUNT = 3
private const val LAST_ADDRESS = "last_address"
private const val LAST_PAYMENT = "last_payment"

/**
 * The few places that fit what the user said: open first, then inside their budget (cost for two scaled to
 * their party size), then rating, then speed. Each pick carries the reason in words, shown on the card.
 */
internal fun bestFits(list: List<FoodRestaurant>, intent: NilaResponse.FoodSearch): List<Pair<FoodRestaurant, String>> {
    val people = intent.people ?: 1
    fun estimate(r: FoodRestaurant) = digitsOf(r.costForTwo)?.let { it * people / 2 }
    fun minutes(r: FoodRestaurant) = digitsOf(r.eta) ?: Int.MAX_VALUE
    fun fits(r: FoodRestaurant) = intent.budgetRupees?.let { budget -> estimate(r)?.let { it <= budget } } ?: true
    val ranked = list.filter { it.open }.sortedWith(
        compareByDescending<FoodRestaurant> { fits(it) }
            .thenByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            .thenBy { minutes(it) },
    )
    val fastest = ranked.minByOrNull { minutes(it) }
    return ranked.take(BEST_FIT_COUNT).map { r ->
        val reasons = buildList {
            val cost = estimate(r)
            when {
                intent.budgetRupees != null && cost != null && fits(r) -> add("About ₹$cost for ${partyWords(people)}, inside ₹${intent.budgetRupees}")
                intent.budgetRupees != null -> add("Over your ₹${intent.budgetRupees} budget")
                cost != null && intent.people != null -> add("About ₹$cost for ${partyWords(people)}")
            }
            if (r == fastest) add("Fastest here") else if (r.eta.isNotBlank()) add(r.eta)
            if (r.rating.isNotBlank()) add("★ ${r.rating}")
        }
        r to reasons.joinToString(" · ")
    }
}

/** Dishes from a search across restaurants; a place known to be closed drops out. */
private fun parseDishes(result: JSONObject, restaurants: List<FoodRestaurant>): List<FoodDish> =
    parseMenuItems(result).mapNotNull { item ->
        val raw = runCatching { JSONObject(item.raw) }.getOrNull() ?: return@mapNotNull null
        if (raw.pick("inStock") == "0") return@mapNotNull null
        val id = raw.pick("restaurant_id", "restaurantId") ?: return@mapNotNull null
        val place = restaurants.firstOrNull { it.id == id }
            ?: FoodRestaurant(id, raw.pick("restaurant_name", "restaurantName").orEmpty(), "", raw.pick("rating").orEmpty(), "", "", "", null, "", open = true)
        if (!place.open) return@mapNotNull null
        FoodDish(item, place, "")
    }

/** Best fit first: inside the budget for the party, then rating, then speed, then price. */
internal fun rankDishes(dishes: List<FoodDish>, intent: NilaResponse.FoodSearch): List<FoodDish> {
    val people = intent.people ?: 1
    fun cost(d: FoodDish) = digitsOf(d.item.chosenSize?.price ?: d.item.price)?.let { it * people }
    fun fits(d: FoodDish) = intent.budgetRupees?.let { budget -> cost(d)?.let { it <= budget } } ?: true
    fun minutes(d: FoodDish) = digitsOf(d.restaurant.eta) ?: Int.MAX_VALUE
    fun match(d: FoodDish) = nameMatch(d.item.name, intent.query)
    val best = dishes.maxOfOrNull { match(it) } ?: 0.0
    val cheapest = dishes.filter { match(it) == best }.mapNotNull { cost(it) }.minOrNull()
    return dishes.sortedWith(
        compareByDescending<FoodDish> { match(it) }
            .thenByDescending { fits(it) }
            .thenByDescending { it.restaurant.rating.toDoubleOrNull() ?: 0.0 }
            .thenBy { minutes(it) }
            .thenBy { cost(it) ?: Int.MAX_VALUE },
    ).map { dish ->
        val reason = buildList {
            if (best > 0 && match(dish) == best) add("Closest match")
            if (cost(dish) != null && cost(dish) == cheapest) add("Lowest price")
            add(dish.restaurant.name)
            if (dish.restaurant.rating.isNotBlank()) add("★ ${dish.restaurant.rating}")
            if (dish.restaurant.eta.isNotBlank()) add(dish.restaurant.eta)
            val total = cost(dish)
            if (intent.people != null && total != null) add("₹$total for ${partyWords(people)}")
            if (intent.budgetRupees != null && !fits(dish)) add("over budget")
        }.joinToString(" · ")
        dish.copy(reason = reason)
    }
}

/**
 * How much of what the user said the dish name covers: 1.0 when every word is there ("paneer tikka roll"
 * for "paneer roll"), 0.5 for half ("paneer wrap"). Swiggy's own order mixes near-misses in.
 */
internal fun nameMatch(name: String, query: String): Double {
    val words = query.lowercase().split(Regex("""\W+""")).filter { it.length > 2 }.ifEmpty { return 0.0 }
    val lower = name.lowercase()
    return words.count { lower.contains(it) }.toDouble() / words.size
}

/** "Best fit" only when the user set limits to fit; with just a dish it is NILA's top pick, and says so. */
internal fun pickLabel(intent: NilaResponse.FoodSearch) =
    if (intent.budgetRupees != null || intent.people != null || intent.preference != null) "BEST FIT" else "TOP PICK"

/**
 * Offers Swiggy lists for this cart, usable ones first, biggest saving first. Swiggy's tool asks to recommend
 * only coupons valid for cash on delivery, so offers it marks online- or card-only are left out.
 */
private fun parseCoupons(result: JSONObject): List<FoodCoupon> =
    allObjects(result)
        .mapNotNull { o ->
            val code = o.pick("couponCode", "coupon_code", "code") ?: return@mapNotNull null
            val terms = listOfNotNull(o.pick("description", "terms", "tnc", "subTitle"), o.pick("title", "header")).joinToString(" ")
            if (Regex("""(?i)\b(card|online payment|upi only|prepaid)\b""").containsMatchIn(terms) && !terms.contains("cash", ignoreCase = true)) return@mapNotNull null
            FoodCoupon(
                code = code,
                title = o.pick("title", "header", "description", "subTitle") ?: code,
                saving = o.pick("discountAmount", "discount_amount", "savings", "discount")?.let(::rupees).orEmpty(),
                usable = o.pick("isApplicable", "applicable", "is_applicable", "valid")?.let { it == "true" || it == "1" } ?: true,
            )
        }
        .distinctBy { it.code }
        .sortedWith(compareByDescending<FoodCoupon> { it.usable }.thenByDescending { digitsOf(it.saving) ?: 0 })

private fun partyWords(people: Int) = if (people == 1) "one" else "$people"

internal fun digitsOf(text: String) = Regex("""\d+""").find(text)?.value?.toIntOrNull()

private val AD_SUFFIX = Regex("""\s*\(Ad\)\s*$""", RegexOption.IGNORE_CASE)

private fun parseRestaurants(result: JSONObject): List<FoodRestaurant> =
    allObjects(result)
        .filter { o -> o.pick("name") != null && o.pick("id", "restaurantId") != null && (o.has("cuisines") || o.has("avgRating") || o.has("rating") || o.has("sla") || o.has("availabilityStatus") || o.has("distanceKm")) }
        .mapNotNull { o ->
            val sla = o.optJSONObject("sla")
            val discount = o.optJSONObject("aggregatedDiscountInfoV3") ?: o.optJSONObject("aggregatedDiscountInfo")
            FoodRestaurant(
                id = o.pick("id", "restaurantId") ?: return@mapNotNull null,
                name = o.pick("name")!!.replace(AD_SUFFIX, "").trim(),
                cuisines = listText(o, "cuisines") ?: o.pick("cuisine").orEmpty(),
                rating = o.pick("avgRatingString", "avgRating", "rating").orEmpty(),
                eta = o.pick("deliveryTimeRange")?.lowercase()
                    ?: o.pick("deliveryTimeMinutes")?.let { "$it mins" }
                    ?: sla?.pick("slaString") ?: sla?.pick("deliveryTime")?.let { "$it min" }
                    ?: o.pick("slaString", "deliveryTime", "eta", "sla")?.let { if (it.all(Char::isDigit)) "$it min" else it }.orEmpty(),
                distance = o.pick("distanceKm")?.let { "$it km" } ?: sla?.pick("lastMileTravelString") ?: o.pick("distance").orEmpty(),
                costForTwo = o.pick("costForTwoMessage", "costForTwoString", "costForTwo").orEmpty(),
                imageUrl = imageUrl(o, "cloudinaryImageId", "imageUrl", "image", "imageId", "img"),
                offer = discount?.let { listOfNotNull(it.pick("header"), it.pick("subHeader")).joinToString(" ") }
                    ?: o.pick("offer", "discountText", "offerText").orEmpty(),
                open = o.pick("availabilityStatus")?.equals("OPEN", ignoreCase = true)
                    ?: o.pick("isOpen", "open")?.toBooleanStrictOrNull() ?: true,
                area = o.pick("areaName", "locality").orEmpty(),
                ratingCount = o.pick("totalRatings", "totalRatingsString").orEmpty(),
                ad = AD_SUFFIX.containsMatchIn(o.pick("name")!!),
            )
        }
        .distinctBy { it.id }
        .take(10)

private fun parseMenuItems(result: JSONObject): List<FoodItem> {
    val objects = allObjects(result)
    // search_menu marks dishes with menu_item_id; without this filter a dish's sizes ("Mini", "Regular")
    // also matched name + id + price and showed up as separate dishes.
    val dishes = objects.filter { it.has("menu_item_id") }.ifEmpty { objects }
    return dishes
        .filter { o -> o.pick("name") != null && o.pick("id", "menu_item_id", "itemId", "menuItemId") != null && o.pick("price", "finalPrice", "defaultPrice", "basePrice") != null }
        .mapNotNull { o ->
            val vegValue = o.pick("isVeg", "veg", "vegClassifier", "itemAttribute")?.uppercase()
            FoodItem(
                id = o.pick("id", "menu_item_id", "itemId", "menuItemId") ?: return@mapNotNull null,
                name = o.pick("name")!!,
                price = rupees(o.pick("finalPrice", "price", "defaultPrice", "basePrice")),
                veg = when (vegValue) {
                    "TRUE", "1", "VEG" -> true
                    "FALSE", "0", "NONVEG", "NON_VEG", "EGG" -> false
                    else -> null
                },
                imageUrl = imageUrl(o, "imageId", "cloudinaryImageId", "imageUrl", "image"),
                description = o.pick("description").orEmpty(),
                customizable = o.has("variantsV2") || o.has("variations") ||
                    o.pick("hasVariants")?.toBooleanStrictOrNull() == true || o.pick("hasAddons")?.toBooleanStrictOrNull() == true,
                raw = o.toString(),
                sizes = parseSizes(o),
            ).let { item -> item.copy(chosenSize = item.sizes.firstOrNull { it.isDefault } ?: item.sizes.firstOrNull()) }
        }
        .distinctBy { it.id }
        .take(24)
}

private fun parseSizes(item: JSONObject): List<FoodSize> {
    val group = (item.optJSONArray("variantsV2") ?: item.optJSONArray("variations"))?.optJSONObject(0) ?: return emptyList()
    val groupId = group.pick("group_id", "groupId", "id") ?: return emptyList()
    val options = group.optJSONArray("variations") ?: group.optJSONArray("variants") ?: return emptyList()
    return (0 until options.length()).mapNotNull { i ->
        val option = options.optJSONObject(i) ?: return@mapNotNull null
        if (option.pick("inStock") == "0") return@mapNotNull null
        FoodSize(
            groupId = groupId,
            id = option.pick("variation_id", "variationId", "id") ?: return@mapNotNull null,
            name = option.pick("name") ?: return@mapNotNull null,
            price = rupees(option.pick("price")),
            isDefault = option.pick("default") == "1",
        )
    }
}

/** One cart line. The size the user picked fills its group; other variant groups take Swiggy's default option. */
private fun cartItemJson(item: FoodItem, quantity: Int): JSONObject {
    val line = JSONObject().put("menu_item_id", item.id).put("quantity", quantity)
    val raw = runCatching { JSONObject(item.raw) }.getOrNull() ?: return line
    for ((sourceKey, targetKey) in listOf("variantsV2" to "variantsV2", "variations" to "variants")) {
        val source = raw.opt(sourceKey) ?: continue
        val chosen = JSONArray()
        allObjects(source).forEach { group ->
            val groupId = group.pick("group_id", "groupId", "id") ?: return@forEach
            val options = group.optJSONArray("variations") ?: group.optJSONArray("variants") ?: return@forEach
            val picked = item.chosenSize?.takeIf { it.groupId == groupId }?.id
            val fallback = (0 until options.length()).mapNotNull { options.optJSONObject(it) }
                .let { all -> all.firstOrNull { it.pick("default") == "1" } ?: all.firstOrNull() }
            val variationId = picked ?: fallback?.pick("variation_id", "variationId", "id") ?: return@forEach
            chosen.put(JSONObject().put("group_id", groupId).put("variation_id", variationId))
        }
        if (chosen.length() > 0) line.put(targetKey, chosen)
    }
    return line
}

private fun parseCart(result: JSONObject, restaurantName: String): FoodCartView {
    val lines = allObjects(result)
        .filter { o -> o.pick("name", "itemName") != null && o.pick("quantity") != null }
        .map { o ->
            FoodCartLine(
                itemId = o.pick("menu_item_id", "menuItemId", "itemId", "id").orEmpty(),
                name = listOfNotNull(
                    o.pick("name", "itemName")!!,
                    o.optJSONArray("variants")?.optJSONObject(0)?.pick("name"),
                ).joinToString(" · "),
                quantity = o.pick("quantity")?.toDoubleOrNull()?.toInt() ?: 1,
                price = rupees(o.pick("final_price", "finalPrice", "total", "subtotal", "price")),
            )
        }
        .distinctBy { it.itemId + it.name }
    val billKeys = listOf(
        "Item total" to listOf("item_total", "itemTotal", "itemsTotal"),
        "Delivery fee" to listOf("delivery_charge", "delivery_fee", "deliveryFee", "deliveryCharges"),
        "Packing" to listOf("packing_charges", "packingCharges"),
        "Platform fee" to listOf("platform_fee", "platformFee"),
        "Taxes and charges" to listOf("taxes_and_charges", "taxes", "gst", "total_taxes", "totalTaxes"),
        "Discount" to listOf("coupon_discount", "discount", "totalDiscount"),
    )
    val bill = billKeys.mapNotNull { (label, keys) ->
        keys.firstNotNullOfOrNull { findString(result, it) }
            ?.takeIf { it.toDoubleOrNull() != 0.0 }
            ?.let { label to rupees(it) }
    }.let { lines ->
        // Swiggy sends delivery_charge 0 with free_delivery_applied; say so instead of dropping the line.
        if (findString(result, "free_delivery_applied") == "true" && lines.none { it.first == "Delivery fee" }) {
            lines.take(1) + ("Delivery fee" to "FREE") + lines.drop(1)
        } else {
            lines
        }
    }
    val total = listOf("to_pay", "toPay", "grand_total", "grandTotal", "order_total", "orderTotal", "finalAmount", "total")
        .firstNotNullOfOrNull { findString(result, it) }
    return FoodCartView(
        restaurantName = findString(result, "restaurantName") ?: restaurantName,
        lines = lines,
        bill = bill,
        total = rupees(total),
    )
}

private fun parsePayOptions(result: JSONObject): List<FoodPayOption> {
    // Swiggy lists phone UPI apps under platforms.mobile.methods; each id ("gpay://upi/") is the
    // intentApp that place_food_order needs. Desktop QR is skipped: NILA runs on the phone.
    val methods = result.optJSONObject("platforms")?.optJSONObject("mobile")?.optJSONArray("methods")
    val options = (0 until (methods?.length() ?: 0)).mapNotNull { i ->
        val method = methods?.optJSONObject(i) ?: return@mapNotNull null
        if (method.pick("kind") != "intent") return@mapNotNull null
        FoodPayOption(
            label = method.pick("displayName") ?: return@mapNotNull null,
            method = "UPI",
            intentApp = method.pick("id") ?: return@mapNotNull null,
            iconUrl = method.pick("iconUrl"),
        )
    }.toMutableList()
    val cod = result.optJSONObject("cod")
    if (cod == null || cod.pick("available") == "true") {
        options += FoodPayOption(cod?.pick("displayName") ?: "Pay on delivery", "Cash", null)
    }
    return options
}
