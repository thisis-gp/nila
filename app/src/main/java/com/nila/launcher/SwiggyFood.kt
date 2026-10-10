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
)

data class FoodCartLine(val itemId: String, val name: String, val quantity: Int, val price: String)

data class FoodCartView(
    val restaurantName: String,
    val lines: List<FoodCartLine>,
    val bill: List<Pair<String, String>>,
    val total: String,
)

data class FoodPayOption(val label: String, val method: String, val intentApp: String?)

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
    data class Addresses(val query: String, val addresses: List<FoodAddress>) : FoodCard
    data class Restaurants(val address: FoodAddress, val query: String, val restaurants: List<FoodRestaurant>) : FoodCard
    data class Menu(val address: FoodAddress, val restaurant: FoodRestaurant, val items: List<FoodItem>) : FoodCard
    data class Cart(val address: FoodAddress, val restaurant: FoodRestaurant, val cart: FoodCartView) : FoodCard
    data class Payment(
        val address: FoodAddress,
        val restaurant: FoodRestaurant,
        val cart: FoodCartView,
        val options: List<FoodPayOption>,
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
    private var client: SwiggyClient? = null
    private var cartRestaurantId: String? = null
    private val cartItems = linkedMapOf<String, Pair<FoodItem, Int>>()

    val connected: Boolean get() = SwiggyAuth.currentSession(context) != null

    private suspend fun call(tool: String, args: JSONObject = JSONObject()): JSONObject? = withContext(Dispatchers.IO) {
        val session = SwiggyAuth.currentSession(context) ?: return@withContext null
        val c = client ?: SwiggyClient(session.accessToken).also { client = it }
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

    suspend fun start(query: String): NilaResponse {
        val result = call("get_addresses") ?: return problem("Loading your addresses")
        return card(FoodCard.Addresses(query, parseAddresses(result)))
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
        call("create_address", args) ?: return problem("Saving the address")
        return start(query)
    }

    suspend fun restaurants(address: FoodAddress, query: String): NilaResponse {
        val result = call("search_restaurants", JSONObject().put("query", query).put("addressId", address.id))
            ?: return problem("Searching restaurants")
        val list = parseRestaurants(result)
        if (list.isEmpty()) return card(FoodCard.Problem("No restaurants found for \"$query\" near ${address.label}."))
        return card(FoodCard.Restaurants(address, query, list))
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
        return card(FoodCard.Menu(address, restaurant, items))
    }

    /** Adds or removes one unit; the full cart is sent each time so Swiggy's cart matches what NILA shows. */
    suspend fun changeItem(address: FoodAddress, restaurant: FoodRestaurant, item: FoodItem, delta: Int): NilaResponse {
        if (cartRestaurantId != restaurant.id) {
            cartItems.clear()
            cartRestaurantId = restaurant.id
        }
        val quantity = (cartItems[item.id]?.second ?: 0) + delta
        if (quantity <= 0) cartItems.remove(item.id) else cartItems[item.id] = item to quantity
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
        return card(FoodCard.Cart(address, restaurant, parseCart(result, restaurant.name)))
    }

    suspend fun payment(address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView): NilaResponse {
        val result = call("get_payment_options", JSONObject().put("addressId", address.id))
            ?: return problem("Loading payment options")
        return card(FoodCard.Payment(address, restaurant, cart, parsePayOptions(result)))
    }

    /** Commit: places a real Swiggy order. Only reachable from the two-tap confirmation. */
    suspend fun place(address: FoodAddress, cart: FoodCartView, option: FoodPayOption): NilaResponse {
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

private fun JSONObject.pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }
        ?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}

private fun allObjects(node: Any?, out: MutableList<JSONObject> = mutableListOf(), depth: Int = 0): List<JSONObject> {
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

private fun findUpiLink(node: Any?): String? =
    allStrings(node).firstOrNull { it.startsWith("upi://") || it.startsWith("intent://") || it.contains("upi://pay") }

/** Swiggy images are either full URLs or Cloudinary IDs on its media CDN. */
private fun imageUrl(o: JSONObject, vararg keys: String): String? {
    val value = o.pick(*keys) ?: return null
    if (value.startsWith("http")) return value
    return "https://media-assets.swiggy.com/swiggy/image/upload/fl_lossy,f_auto,q_auto,w_300/$value"
}

/** Swiggy's internal APIs sometimes give paise; a single dish above ₹3000 is far rarer than a paise value. */
private fun rupees(value: String?): String {
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

private fun parseRestaurants(result: JSONObject): List<FoodRestaurant> =
    allObjects(result)
        .filter { o -> o.pick("name") != null && o.pick("id", "restaurantId") != null && (o.has("cuisines") || o.has("avgRating") || o.has("rating") || o.has("sla") || o.has("availabilityStatus") || o.has("distanceKm")) }
        .mapNotNull { o ->
            val sla = o.optJSONObject("sla")
            val discount = o.optJSONObject("aggregatedDiscountInfoV3") ?: o.optJSONObject("aggregatedDiscountInfo")
            FoodRestaurant(
                id = o.pick("id", "restaurantId") ?: return@mapNotNull null,
                name = o.pick("name")!!,
                cuisines = listText(o, "cuisines") ?: o.pick("cuisine").orEmpty(),
                rating = o.pick("avgRatingString", "avgRating", "rating").orEmpty(),
                eta = sla?.pick("slaString") ?: sla?.pick("deliveryTime")?.let { "$it min" }
                    ?: o.pick("slaString", "deliveryTime", "eta", "sla")?.let { if (it.all(Char::isDigit)) "$it min" else it }.orEmpty(),
                distance = o.pick("distanceKm")?.let { "$it km" } ?: sla?.pick("lastMileTravelString") ?: o.pick("distance").orEmpty(),
                costForTwo = o.pick("costForTwoMessage", "costForTwoString", "costForTwo").orEmpty(),
                imageUrl = imageUrl(o, "cloudinaryImageId", "imageUrl", "image", "imageId", "img"),
                offer = discount?.let { listOfNotNull(it.pick("header"), it.pick("subHeader")).joinToString(" ") }
                    ?: o.pick("offer", "discountText", "offerText").orEmpty(),
                open = o.pick("availabilityStatus")?.equals("OPEN", ignoreCase = true)
                    ?: o.pick("isOpen", "open")?.toBooleanStrictOrNull() ?: true,
            )
        }
        .distinctBy { it.id }
        .take(10)

private fun parseMenuItems(result: JSONObject): List<FoodItem> =
    allObjects(result)
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
            )
        }
        .distinctBy { it.id }
        .take(24)

/** One cart line. Customizable items get the first option of each variant group (shown as "default" in the UI). */
private fun cartItemJson(item: FoodItem, quantity: Int): JSONObject {
    val line = JSONObject().put("menu_item_id", item.id).put("quantity", quantity)
    val raw = runCatching { JSONObject(item.raw) }.getOrNull() ?: return line
    for ((sourceKey, targetKey) in listOf("variantsV2" to "variantsV2", "variations" to "variants")) {
        val source = raw.opt(sourceKey) ?: continue
        val chosen = JSONArray()
        allObjects(source).forEach { group ->
            val groupId = group.pick("group_id", "groupId", "id") ?: return@forEach
            val options = group.optJSONArray("variations") ?: group.optJSONArray("variants") ?: return@forEach
            val first = options.optJSONObject(0) ?: return@forEach
            val variationId = first.pick("variation_id", "variationId", "id") ?: return@forEach
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
                name = o.pick("name", "itemName")!!,
                quantity = o.pick("quantity")?.toDoubleOrNull()?.toInt() ?: 1,
                price = rupees(o.pick("final_price", "finalPrice", "total", "subtotal", "price")),
            )
        }
        .distinctBy { it.itemId + it.name }
    val billKeys = listOf(
        "Item total" to listOf("item_total", "itemTotal", "itemsTotal"),
        "Delivery fee" to listOf("delivery_fee", "deliveryFee", "deliveryCharges"),
        "Packing" to listOf("packing_charges", "packingCharges"),
        "Platform fee" to listOf("platform_fee", "platformFee"),
        "Taxes" to listOf("taxes", "gst", "total_taxes", "totalTaxes"),
        "Discount" to listOf("coupon_discount", "discount", "totalDiscount"),
    )
    val bill = billKeys.mapNotNull { (label, keys) ->
        keys.firstNotNullOfOrNull { findString(result, it) }
            ?.takeIf { it.toDoubleOrNull() != 0.0 }
            ?.let { label to rupees(it) }
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
    val options = allObjects(result)
        .filter { o -> o.pick("intentApp", "appName", "displayName", "packageName") != null }
        .map { o ->
            FoodPayOption(
                label = o.pick("displayName", "appName", "name", "label") ?: "UPI",
                method = o.pick("paymentMethod", "method") ?: "UPI",
                intentApp = o.pick("intentApp", "packageName", "app"),
            )
        }
        .distinctBy { it.label }
        .toMutableList()
    if (options.none { it.method.equals("UPI", ignoreCase = true) } && allStrings(result).any { it.contains("UPI", ignoreCase = true) }) {
        options += FoodPayOption("UPI", "UPI", null)
    }
    options += FoodPayOption("Cash on delivery", "Cash", null)
    return options
}
